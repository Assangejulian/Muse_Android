package com.androidagent.app.agent

import android.content.Context
import com.androidagent.app.accessibility.AgentAccessibilityService
import com.androidagent.app.apps.AppCatalog
import com.androidagent.app.data.SecureSettings
import com.androidagent.app.network.DeepSeekClient
import com.androidagent.app.network.PlannedAction
import com.androidagent.app.network.PlannerTurn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

enum class RuntimeOutcome {
    SUCCESS,
    TRANSIENT_NETWORK_ERROR,
    ACCESSIBILITY_DISCONNECTED,
    AGENT_BUSY,
    PERMANENT_PLAN_ERROR,
    SAFETY_BLOCKED,
    USER_CANCELLED,
    TIMEOUT,
    INTERNAL_ERROR,
}

data class RuntimeResult(
    val outcome: RuntimeOutcome,
    val reason: String,
    val runId: String? = null,
) {
    val succeeded: Boolean get() = outcome == RuntimeOutcome.SUCCESS

    companion object {
        fun failure(outcome: RuntimeOutcome, reason: String, runId: String? = null): RuntimeResult = RuntimeResult(outcome, reason, runId)
    }
}

private data class ScreenshotCapture(
    val dataUrl: String? = null,
    val failure: String? = null,
    val fatal: Boolean = false,
)

private data class RecoveryExecution(
    val success: Boolean,
    val observation: Observation,
    val detail: String,
)

class AgentRuntime(
    private val context: Context,
    private val settings: SecureSettings,
    private val service: AgentAccessibilityService,
    private val onPhase: (step: Int, phase: String) -> Unit,
    private val onLog: (String) -> Unit,
    private val onAction: (String) -> Unit = {},
    private val goalOverride: String? = null,
    private val runIdOverride: String? = null,
    private val cancellationOutcomeProvider: () -> RuntimeOutcome? = { null },
) {
    private val client = DeepSeekClient()
    private val executionHistory = ExecutionHistory()
    private val recoveryPolicy = RecoveryPolicy()
    private var lastWaitTimedOut = false

    suspend fun run(): RuntimeResult {
        val immutableGoal = goalOverride?.trim().takeUnless { it.isNullOrBlank() } ?: settings.taskGoal
        val apiKey = settings.apiKey
        val apps = AppCatalog(context.applicationContext).list()
        val launchablePackages = apps.mapTo(mutableSetOf()) { it.packageName }
        val appCatalog = apps.joinToString("\n") { "${it.label} | ${it.packageName}" }.take(16_000)
        var lockedPackage = resolveTarget(settings.targetPackage, immutableGoal, apps.map { it.label to it.packageName })
        val goalContext = GoalContract.interpret(immutableGoal)
        var targetHint = apps.firstOrNull { it.packageName == lockedPackage }?.label
            ?: settings.targetPackage.ifBlank { immutableGoal.take(80) }
        var packagePolicy = PackagePolicy(
            allowedPackages = lockedPackage?.let { mutableSetOf(it) } ?: mutableSetOf(),
            primaryPackage = lockedPackage,
        )

        // Vision is never enabled implicitly. The UI toggle is the user's data-sharing decision.
        val visionKey = settings.visionApiKey.ifBlank { settings.apiKeyFor("qwen") }
        val useVision = settings.visionEnabled && visionKey.isNotBlank()
        val actorKey = if (useVision) visionKey else apiKey
        val actorBaseUrl = if (useVision) settings.visionBaseUrl else settings.modelBaseUrl
        val actorModel = if (useVision) settings.visionModelName else settings.modelName
        val traceStore = AgentTraceStore(context)
        val runId = traceStore.startRun(immutableGoal, actorModel, runIdOverride)
        var activeBindings: PredicateBindingStore? = null

        fun finish(outcome: RuntimeOutcome, reason: String): RuntimeResult {
            traceStore.event(
                runId,
                "RUN_FINISHED",
                mapOf("outcome" to outcome.name, "reasonCode" to outcome.name),
            )
            activeBindings?.rollbackRun(runId)
            traceStore.finish(runId, if (outcome == RuntimeOutcome.SUCCESS) "SUCCEEDED" else "FAILED", reason)
            return RuntimeResult(outcome, reason, runId)
        }

        return try {
            withTimeout(RUN_TIMEOUT_MS) {
                onPhase(0, "Compiling")
                if (settings.visionEnabled && !useVision) {
                    onLog("Vision is enabled but no vision API key is configured; using node-only mode")
                }
                val goalSafetyFailure = SensitiveOperationPolicy.validateGoal(immutableGoal).exceptionOrNull()
                if (goalSafetyFailure != null) {
                    return@withTimeout finish(RuntimeOutcome.SAFETY_BLOCKED, "SAFETY_BLOCKED: ${goalSafetyFailure.message.orEmpty()}")
                }

                var plan = try {
                    client.createTaskPlan(
                        apiKey,
                        settings.modelBaseUrl,
                        settings.modelName,
                        goalContext,
                        appCatalog,
                        targetHint,
                        provider = settings.currentProvider,
                    )
                } catch (failure: TaskPlanException) {
                    return@withTimeout finish(RuntimeOutcome.PERMANENT_PLAN_ERROR, failure.message ?: "Manager plan failed after retries")
                }
                validatePlanPackages(plan, launchablePackages)
                if (lockedPackage == null) {
                    lockedPackage = resolveTarget(plan.targetAppHint, immutableGoal, apps.map { it.label to it.packageName })
                    targetHint = apps.firstOrNull { it.packageName == lockedPackage }?.label ?: targetHint
                    if (lockedPackage != null) {
                        packagePolicy = packagePolicy.copy(
                            allowedPackages = (packagePolicy.allowedPackages + lockedPackage!!).toMutableSet(),
                            primaryPackage = lockedPackage,
                        )
                    }
                }
                packagePolicy = mergePlanPackages(packagePolicy, plan, launchablePackages)
                plan = ensureLaunchMilestone(plan, lockedPackage)

                var ledger = RunLedger(plan)
                val predicateBindings = PredicateBindingStore()
                activeBindings = predicateBindings
                fun expectedRecoveryPackage(milestone: TaskMilestone?): String? =
                    expectedPackage(milestone, lockedPackage, predicateBindings)
                var guard = ToolGuard(plan, packagePolicy)
                val history = mutableListOf("MANAGER_PLAN:\n${plan.compactText(0)}")
                val toolTurns = mutableListOf<PlannerTurn>()
                var replans = 0
                var modelFailures = 0
                val evidenceCounters = StopGateEvidenceCounters()
                var finishRejections = 0
                var pendingReplanReason: String? = null

                traceStore.event(runId, "PLAN_CREATED", mapOf("plan" to plan.compactText(0)))
                onLog("Plan ready: ${plan.milestones.size} milestones")

                for (step in 1..MAX_TOOL_CALLS) {
                    onPhase(step, "Observing")
                    onAction("")
                    val rawBefore = observeWithPackage(lockedPackage)
                    if (step == 1 && !lockedPackage.isNullOrBlank() && rawBefore.packageName != lockedPackage) {
                        val launch = AgentAction.LaunchApp(lockedPackage!!)
                        val launchSafetyFailure = SafetyGuard.validate(
                            launch,
                            rawBefore,
                            packagePolicy,
                            launchablePackages,
                            goalContext,
                        ).exceptionOrNull()
                        if (launchSafetyFailure != null) {
                            return@withTimeout finish(RuntimeOutcome.SAFETY_BLOCKED, "SAFETY_BLOCKED: ${launchSafetyFailure.message.orEmpty()}")
                        }
                        onPhase(step, "Acting")
                        onAction(describeAction(launch))
                        if (!service.execute(launch, rawBefore)) {
                            val recovery = recoveryPolicy.decide(
                                RecoveryContext(
                                    expectedPackage = lockedPackage,
                                    currentPackage = rawBefore.packageName,
                                    currentMilestoneId = "launch",
                                    reason = RecoveryReason.APP_NOT_RESPONDING,
                                    failedAction = launch,
                                ),
                            )
                            val recovered = executeRecovery(recovery, step, rawBefore, lockedPackage, executionHistory, lockedPackage, ledger.currentMilestone, launch, packagePolicy, launchablePackages, goalContext)
                            traceStore.event(runId, "RECOVERY", mapOf("reason" to recovery.reason.name, "action" to recovery.action.name, "result" to recovered.detail))
                            if (!recovered.success) return@withTimeout finish(RuntimeOutcome.ACCESSIBILITY_DISCONNECTED, "Unable to launch the locked target app: ${recovered.detail}")
                            continue
                        }
                        val launched = awaitStableObservation(rawBefore, launch)
                        if (launched.packageName != lockedPackage) {
                            return@withTimeout finish(RuntimeOutcome.TIMEOUT, "Target app did not become ready before the launch deadline")
                        }
                        evidenceCounters.successfulMutatingActions += 1
                        history += "LOCAL_TOOL_RESULT: launched locked package $lockedPackage without exposing the previous app"
                        continue
                    }
                    val packageFailure = packageBoundaryFailure(rawBefore, lockedPackage, packagePolicy)
                    if (packageFailure != null) {
                        val decision = recoveryPolicy.decide(
                            RecoveryContext(
                                expectedPackage = expectedRecoveryPackage(ledger.currentMilestone),
                                currentPackage = rawBefore.packageName,
                                currentMilestoneId = ledger.currentMilestone?.id,
                                currentMilestoneKind = ledger.currentMilestone?.kind,
                                reason = RecoveryReason.WRONG_PACKAGE,
                            ),
                        )
                            val recovered = executeRecovery(decision, step, rawBefore, lockedPackage, executionHistory, expectedRecoveryPackage(ledger.currentMilestone), ledger.currentMilestone, packagePolicy = packagePolicy, launchablePackages = launchablePackages, goal = goalContext)
                        traceStore.event(runId, "RECOVERY", mapOf("reason" to decision.reason.name, "action" to decision.action.name, "result" to recovered.detail))
                        if (!recovered.success) return@withTimeout finish(RuntimeOutcome.INTERNAL_ERROR, recovered.detail)
                        if (decision.action == RecoveryAction.REPLAN) pendingReplanReason = packageFailure
                        continue
                    }

                    val privacy = if (lockedPackage == null) {
                        // Until a target is selected, expose only the goal and installed app catalog to the model.
                        PrivacyDecision(allowed = true, observation = Observation("", emptyList()))
                    } else {
                        PrivacyGuard.prepare(rawBefore)
                    }
                    if (!privacy.allowed) {
                        val reason = "Stopped before model access: ${privacy.reason}"
                        traceStore.event(runId, "PRIVACY_BLOCKED", mapOf("reason" to privacy.reason, "package" to rawBefore.packageName))
                        return@withTimeout finish(RuntimeOutcome.SAFETY_BLOCKED, reason)
                    }
                    val before = privacy.observation
                    ledger.observe(before)
                    traceStore.event(
                        runId,
                        "OBSERVATION",
                        mapOf(
                            "step" to step,
                            "observationId" to before.observationId,
                            "package" to before.packageName,
                            "milestone" to ledger.currentMilestone?.id,
                        ),
                    )

                    val milestone = ledger.currentMilestone
                    val pendingGap = pendingReplanReason
                    if (pendingGap != null) {
                        if (replans >= MAX_REPLANS) return@withTimeout finish(RuntimeOutcome.PERMANENT_PLAN_ERROR, "recovery replan budget exhausted: $pendingGap")
                        onPhase(step, "Replanning")
                        val revised = try {
                            replan(plan, ledger, goalContext, appCatalog, targetHint, apiKey, settings.modelBaseUrl, settings.modelName, pendingGap)
                        } catch (failure: TaskPlanException) {
                            return@withTimeout finish(RuntimeOutcome.PERMANENT_PLAN_ERROR, failure.message ?: "Manager replan failed after retries")
                        }
                        validateRevisedPlan(plan, revised, launchablePackages)
                        val completed = ledger.currentMilestoneIndex
                        val prepared = prepareRevisedPlan(revised, plan, completed, lockedPackage)
                        predicateBindings.retainCompleted(
                            plan,
                            prepared.first,
                            plan.milestones.take(completed).mapTo(mutableSetOf()) { it.id },
                        )
                        plan = prepared.first
                        packagePolicy = mergePlanPackages(packagePolicy, plan, launchablePackages)
                        ledger.replacePlan(plan, prepared.second)
                        guard = ToolGuard(plan, packagePolicy)
                        replans += 1
                        history += "RECOVERY_REPLAN: $pendingGap"
                        pendingReplanReason = null
                        continue
                    }
                    if (milestone == null) {
                        val verified = verifyStopGate(
                            step,
                            goalContext,
                            before,
                            history,
                            plan,
                            ledger,
                            useVision,
                            actorKey,
                            actorBaseUrl,
                            actorModel,
                            evidenceCounters,
                            lockedPackage,
                            packagePolicy,
                            predicateBindings,
                            runId,
                        )
                        if (verified.done) {
                            return@withTimeout finish(RuntimeOutcome.SUCCESS, verified.reason)
                        }
                        if (replans >= MAX_REPLANS) return@withTimeout finish(RuntimeOutcome.PERMANENT_PLAN_ERROR, verified.reason)
                        val revised = replan(
                            plan,
                            ledger,
                            goalContext,
                            appCatalog,
                            targetHint,
                            apiKey,
                            settings.modelBaseUrl,
                            settings.modelName,
                            verified.reason,
                        )
                        validateRevisedPlan(plan, revised, launchablePackages)
                        val completed = ledger.currentMilestoneIndex
                        val prepared = prepareRevisedPlan(revised, plan, completed, lockedPackage)
                        predicateBindings.retainCompleted(
                            plan,
                            prepared.first,
                            plan.milestones.take(completed).mapTo(mutableSetOf()) { it.id },
                        )
                        plan = prepared.first
                        packagePolicy = mergePlanPackages(packagePolicy, plan, launchablePackages)
                        ledger.replacePlan(plan, prepared.second)
                        guard = ToolGuard(plan, packagePolicy)
                        replans += 1
                        history += "REPLAN_AFTER_STOP_GATE: ${verified.reason}"
                        continue
                    }

                    val deterministicBefore = MilestoneEvaluator.evaluate(milestone, plan, before, lockedPackage, predicateBindings, runId = runId)
                    if (deterministicBefore.proven) {
                        val proof = deterministicBefore.details.joinToString(" | ")
                        evidenceCounters.deterministicEvidenceCount += 1
                        evidenceCounters.verifiedMilestones += 1
                        predicateBindings.markVerified(milestone.id)
                        history += "MILESTONE_PROVEN: ${ledger.advance(proof)}"
                        recoveryPolicy.resetFailures()
                        traceStore.event(runId, "MILESTONE_PROVEN", mapOf("id" to milestone.id, "evidence" to proof, "source" to "deterministic"))
                        onLog("Milestone ${milestone.id} verified locally")
                        continue
                    }

                    val cycle = ledger.cyclePeriod()
                    if (ledger.noProgressCount >= MAX_NO_PROGRESS || (cycle != null && ledger.noProgressCount >= 2)) {
                        val recovery = recoveryPolicy.decide(
                            RecoveryContext(
                                expectedPackage = expectedRecoveryPackage(milestone),
                                currentPackage = before.packageName,
                                currentMilestoneId = milestone.id,
                                currentMilestoneKind = milestone.kind,
                                reason = if (cycle != null) RecoveryReason.ABAB_LOOP else RecoveryReason.SCREEN_UNCHANGED,
                            ),
                        )
                        history += "RECOVERY: ${recovery.reason} -> ${recovery.action} (${recovery.detail})"
                        val recovered = executeRecovery(recovery, step, before, lockedPackage, executionHistory, expectedRecoveryPackage(milestone), milestone, packagePolicy = packagePolicy, launchablePackages = launchablePackages, goal = goalContext)
                        traceStore.event(runId, "RECOVERY", mapOf("reason" to recovery.reason.name, "action" to recovery.action.name, "result" to recovered.detail))
                        if (!recovered.success) return@withTimeout finish(RuntimeOutcome.INTERNAL_ERROR, recovered.detail)
                        if (recovery.action == RecoveryAction.REPLAN) pendingReplanReason = recovery.reason.name
                        continue
                    }

                    onPhase(step, "Planning")
                    val screenshotCapture = if (useVision && lockedPackage != null) {
                        captureBoundScreenshot(before, lockedPackage, packagePolicy)
                    } else {
                        ScreenshotCapture()
                    }
                    if (screenshotCapture.failure != null) {
                        val reason = screenshotCapture.failure
                        onLog(reason)
                        traceStore.event(runId, "SCREENSHOT_REJECTED", mapOf("reason" to reason))
                        if (screenshotCapture.fatal) return@withTimeout finish(RuntimeOutcome.SAFETY_BLOCKED, reason)
                        history += "OBSERVATION_STALE: $reason"
                        continue
                    }
                    val screenshot = screenshotCapture.dataUrl
                    val workflowAction = guard.requiredWorkflowAction(before, milestone)
                    var planned: PlannedAction? = null
                    val proposed = if (workflowAction != null) {
                        onLog("Local workflow tool: ${describeAction(workflowAction)}")
                        workflowAction
                    } else {
                        try {
                            client.planAction(
                                apiKey = actorKey,
                                baseUrl = actorBaseUrl,
                                model = actorModel,
                                goal = immutableGoal,
                                allowedPackage = lockedPackage,
                                appCatalog = appCatalog,
                                observation = before,
                                history = (history + executionHistory.promptLines()).takeLast(24),
                                screenshotDataUrl = screenshot,
                                harnessState = "CURRENT MILESTONE: ${milestone.id} ${milestone.objective}\n" +
                                    "SUCCESS CONTRACT: ${milestone.successEvidence}\n${ledger.planText(predicateBindings)}",
                                toolTurns = toolTurns.takeLast(MAX_MODEL_TOOL_TURNS),
                                provider = settings.currentProvider,
                                primaryPackage = packagePolicy.primaryPackage,
                                currentPackage = before.packageName,
                                allowedPackages = packagePolicy.allowedPackages,
                            ).also { planned = it }.action
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Throwable) {
                            modelFailures += 1
                            val reason = "planner error: ${error.message.orEmpty()}"
                            history += "MODEL_ERROR: $reason"
                            onLog(reason)
                            traceStore.event(runId, "MODEL_ERROR", mapOf("reason" to reason))
                            if (modelFailures >= MAX_MODEL_FAILURES || isFatalModelError(error)) {
                                val outcome = if (isFatalModelError(error)) {
                                    RuntimeOutcome.PERMANENT_PLAN_ERROR
                                } else {
                                    RuntimeOutcome.TRANSIENT_NETWORK_ERROR
                                }
                                return@withTimeout finish(outcome, reason)
                            }
                            val recovery = recoveryPolicy.decide(
                                RecoveryContext(
                                    expectedPackage = expectedRecoveryPackage(milestone),
                                    currentPackage = before.packageName,
                                    currentMilestoneId = milestone.id,
                                    currentMilestoneKind = milestone.kind,
                                    failedAction = null,
                                    reason = RecoveryReason.NETWORK_ERROR,
                                ),
                            )
                            val recovered = executeRecovery(recovery, step, before, lockedPackage, executionHistory, expectedRecoveryPackage(milestone), milestone, packagePolicy = packagePolicy, launchablePackages = launchablePackages, goal = goalContext)
                            traceStore.event(runId, "RECOVERY", mapOf("reason" to recovery.reason.name, "action" to recovery.action.name, "result" to recovered.detail))
                            if (!recovered.success) return@withTimeout finish(RuntimeOutcome.TRANSIENT_NETWORK_ERROR, recovered.detail)
                            if (recovery.action == RecoveryAction.REPLAN) pendingReplanReason = reason
                            ledger.record(
                                StepTrace(
                                    milestone.id,
                                    before.observationId,
                                    "invalid_model_output",
                                    before.observationId,
                                    TransitionJudgement.NO_PROGRESS,
                                    reason,
                                ),
                            )
                            continue
                        }
                    }
                    modelFailures = 0
                    onAction(describeAction(proposed))
                    traceStore.event(
                        runId,
                        "TOOL_PROPOSED",
                        mapOf(
                            "step" to step,
                            "milestone" to milestone.id,
                            "action" to TraceSanitizer.action(proposed),
                            "actionType" to TraceSanitizer.actionType(proposed),
                            "target" to actionTarget(proposed, before),
                            "basedOn" to before.observationId,
                        ),
                    )

                    if (proposed is AgentAction.Finish) {
                        onPhase(step, "Verifying")
                        val verification = verifyStopGate(
                            step,
                            goalContext,
                            before,
                            history,
                            plan,
                            ledger,
                            useVision,
                            actorKey,
                            actorBaseUrl,
                            actorModel,
                            evidenceCounters,
                            lockedPackage,
                            packagePolicy,
                            predicateBindings,
                            runId,
                        )
                        if (verification.done) {
                            recordTurn(toolTurns, planned, toolResultJson(true, proposed, before, before, "verified_complete", verification.reason))
                            return@withTimeout finish(RuntimeOutcome.SUCCESS, verification.reason)
                        }
                        finishRejections += 1
                        val feedback = toolResultJson(false, proposed, before, before, "finish_rejected", verification.reason)
                        recordTurn(toolTurns, planned, feedback)
                        history += "STOP_GATE_REJECTED: ${verification.reason}"
                        ledger.record(StepTrace(milestone.id, before.observationId, proposed.toString(), before.observationId, TransitionJudgement.NO_PROGRESS, verification.reason))
                        onLog("Completion not yet proven: ${verification.reason.take(120)}")
                        if (finishRejections >= 2 && replans < MAX_REPLANS) {
                            onPhase(step, "Replanning")
                            val revised = replan(plan, ledger, goalContext, appCatalog, targetHint, apiKey, settings.modelBaseUrl, settings.modelName, verification.reason)
                            validateRevisedPlan(plan, revised, launchablePackages)
                            val completed = ledger.currentMilestoneIndex
                            val prepared = prepareRevisedPlan(revised, plan, completed, lockedPackage)
                            predicateBindings.retainCompleted(
                                plan,
                                prepared.first,
                                plan.milestones.take(completed).mapTo(mutableSetOf()) { it.id },
                            )
                            plan = prepared.first
                            packagePolicy = mergePlanPackages(packagePolicy, plan, launchablePackages)
                            ledger.replacePlan(plan, prepared.second)
                            guard = ToolGuard(plan, packagePolicy)
                            replans += 1
                            finishRejections = 0
                        }
                        continue
                    }

                    if (proposed is AgentAction.Fail) {
                        val feedback = toolResultJson(false, proposed, before, before, "actor_blocked", proposed.reason)
                        recordTurn(toolTurns, planned, feedback)
                        history += "ACTOR_BLOCKED: ${proposed.reason}"
                        if (replans >= MAX_REPLANS) return@withTimeout finish(RuntimeOutcome.PERMANENT_PLAN_ERROR, proposed.reason)
                        onPhase(step, "Replanning")
                        val revised = replan(plan, ledger, goalContext, appCatalog, targetHint, apiKey, settings.modelBaseUrl, settings.modelName, proposed.reason)
                        validateRevisedPlan(plan, revised, launchablePackages)
                        val completed = ledger.currentMilestoneIndex
                        val prepared = prepareRevisedPlan(revised, plan, completed, lockedPackage)
                        predicateBindings.retainCompleted(
                            plan,
                            prepared.first,
                            plan.milestones.take(completed).mapTo(mutableSetOf()) { it.id },
                        )
                        plan = prepared.first
                        packagePolicy = mergePlanPackages(packagePolicy, plan, launchablePackages)
                        ledger.replacePlan(plan, prepared.second)
                        guard = ToolGuard(plan, packagePolicy)
                        replans += 1
                        continue
                    }

                    // The planner may have spent time on a model call. Always
                    // refresh the execution observation before any local guard
                    // or side effect, regardless of action type.
                    val executionObservation = service.observe()
                    val preflight = RuntimeStepExecutor.preflight(
                        guard = guard,
                        ledger = ledger,
                        proposed = proposed,
                        // Compare fresh execution state with the local raw
                        // snapshot, not the redacted model copy whose
                        // fingerprint may intentionally differ.
                        planningObservation = rawBefore,
                        executionObservation = executionObservation,
                        milestone = milestone,
                        packagePolicy = packagePolicy,
                        launchablePackages = launchablePackages,
                        goal = goalContext,
                    )
                    if (preflight.stale) {
                        val reason = "screen changed before tool dispatch; re-observe before acting"
                        val current = PrivacyGuard.prepare(executionObservation).observation
                        recordTurn(toolTurns, planned, toolResultJson(false, proposed, before, current, "stale_observation", reason))
                        history += "PRE_TOOL_BLOCKED: $proposed because $reason"
                        ledger.observe(current)
                        ledger.record(StepTrace(milestone.id, before.observationId, proposed.toString(), current.observationId, TransitionJudgement.NO_PROGRESS, reason))
                        continue
                    }

                    // Guard, local safety, and duplicate checks share this
                    // exact fresh snapshot before binding or execution.
                    val guarded = preflight.guarded
                    val action = preflight.action
                    if (action == null) {
                        val reason = guarded.rejection ?: "pre-tool policy rejected the action"
                        val feedback = toolResultJson(false, proposed, before, before, "policy_rejected", reason)
                        recordTurn(toolTurns, planned, feedback)
                        history += "PRE_TOOL_BLOCKED: $proposed because $reason"
                        ledger.record(StepTrace(milestone.id, before.observationId, proposed.toString(), before.observationId, TransitionJudgement.NO_PROGRESS, reason))
                        traceStore.event(runId, "PRE_TOOL_BLOCKED", mapOf("action" to TraceSanitizer.action(proposed), "actionType" to TraceSanitizer.actionType(proposed), "reasonCode" to TraceSanitizer.reasonCode(reason)))
                        onLog("Tool blocked: $reason")
                        val recovery = recoveryPolicy.decide(
                            RecoveryContext(
                                expectedPackage = expectedRecoveryPackage(milestone),
                                currentPackage = before.packageName,
                                currentMilestoneId = milestone.id,
                                currentMilestoneKind = milestone.kind,
                                failedAction = proposed,
                                reason = classifyRecoveryReason(proposed, reason),
                            ),
                        )
                        val recovered = executeRecovery(recovery, step, before, lockedPackage, executionHistory, expectedRecoveryPackage(milestone), milestone, proposed, packagePolicy, launchablePackages, goalContext)
                        traceStore.event(runId, "RECOVERY", mapOf("reason" to recovery.reason.name, "action" to recovery.action.name, "result" to recovered.detail))
                        if (!recovered.success) return@withTimeout finish(RuntimeOutcome.INTERNAL_ERROR, recovered.detail)
                        if (recovery.action == RecoveryAction.REPLAN) pendingReplanReason = reason
                        continue
                    }

                    if (lockedPackage == null && action !is AgentAction.LaunchApp) {
                        val reason = "launch_app must select one installed target before any screen-dependent tool"
                        recordTurn(toolTurns, planned, toolResultJson(false, action, before, before, "target_required", reason))
                        history += "PRE_TOOL_BLOCKED: $action because $reason"
                        ledger.record(StepTrace(milestone.id, before.observationId, action.toString(), before.observationId, TransitionJudgement.NO_PROGRESS, reason))
                        val recovery = recoveryPolicy.decide(
                            RecoveryContext(
                                expectedPackage = expectedRecoveryPackage(milestone),
                                currentPackage = before.packageName,
                                currentMilestoneId = milestone.id,
                                currentMilestoneKind = milestone.kind,
                                failedAction = action,
                                reason = RecoveryReason.TARGET_MISSING,
                            ),
                        )
                        val recovered = executeRecovery(recovery, step, before, lockedPackage, executionHistory, expectedRecoveryPackage(milestone), milestone, action, packagePolicy, launchablePackages, goalContext)
                        if (!recovered.success) return@withTimeout finish(RuntimeOutcome.INTERNAL_ERROR, recovered.detail)
                        if (recovery.action == RecoveryAction.REPLAN) pendingReplanReason = reason
                        continue
                    }

                    if (action is AgentAction.TapPoint && screenshot == null) {
                        val reason = "tap_point requires an explicitly enabled current screenshot"
                        recordTurn(toolTurns, planned, toolResultJson(false, action, before, before, "policy_rejected", reason))
                        history += "PRE_TOOL_BLOCKED: $reason"
                        ledger.record(StepTrace(milestone.id, before.observationId, action.toString(), before.observationId, TransitionJudgement.NO_PROGRESS, reason))
                        continue
                    }

                    var provisionalBindings = emptyList<ProvisionalPredicateBinding>()
                    val safetyFailure = preflight.safetyFailure
                    if (safetyFailure != null) {
                        val reason = safetyFailure.ifBlank { "safety policy rejected the action" }
                        recordTurn(toolTurns, planned, toolResultJson(false, action, before, before, "safety_rejected", reason))
                        history += "PRE_TOOL_BLOCKED: $action because $reason"
                        ledger.record(StepTrace(milestone.id, before.observationId, action.toString(), before.observationId, TransitionJudgement.NO_PROGRESS, reason))
                        traceStore.event(runId, "PRE_TOOL_BLOCKED", mapOf("action" to TraceSanitizer.action(action), "actionType" to TraceSanitizer.actionType(action), "reasonCode" to TraceSanitizer.reasonCode(reason)))
                        onLog("Safety guard blocked: $reason")
                        val recovery = recoveryPolicy.decide(
                            RecoveryContext(
                                expectedPackage = expectedRecoveryPackage(milestone),
                                currentPackage = before.packageName,
                                currentMilestoneId = milestone.id,
                                currentMilestoneKind = milestone.kind,
                                failedAction = action,
                                reason = classifyRecoveryReason(action, reason),
                            ),
                        )
                        val recovered = executeRecovery(recovery, step, before, lockedPackage, executionHistory, expectedRecoveryPackage(milestone), milestone, action, packagePolicy, launchablePackages, goalContext)
                        traceStore.event(runId, "RECOVERY", mapOf("reason" to recovery.reason.name, "action" to recovery.action.name, "result" to recovered.detail))
                        if (!recovered.success) return@withTimeout finish(RuntimeOutcome.INTERNAL_ERROR, recovered.detail)
                        if (recovery.action == RecoveryAction.REPLAN) pendingReplanReason = reason
                        continue
                    }

                    val repeatedReason = preflight.repeatedReason
                    if (repeatedReason != null) {
                        recordTurn(toolTurns, planned, toolResultJson(false, action, before, before, "duplicate_action", repeatedReason))
                        history += "PRE_TOOL_BLOCKED: $action because $repeatedReason"
                        ledger.record(StepTrace(milestone.id, before.observationId, action.toString(), before.observationId, TransitionJudgement.NO_PROGRESS, repeatedReason))
                        onLog("Duplicate strategy blocked")
                        val recovery = recoveryPolicy.decide(
                            RecoveryContext(
                                expectedPackage = expectedRecoveryPackage(milestone),
                                currentPackage = executionObservation.packageName,
                                currentMilestoneId = milestone.id,
                                currentMilestoneKind = milestone.kind,
                                failedAction = action,
                                reason = RecoveryReason.REPEATED_ACTION,
                            ),
                        )
                        val recovered = executeRecovery(recovery, step, executionObservation, lockedPackage, executionHistory, expectedRecoveryPackage(milestone), milestone, action, packagePolicy, launchablePackages, goalContext)
                        traceStore.event(runId, "RECOVERY", mapOf("reason" to recovery.reason.name, "action" to recovery.action.name, "result" to recovered.detail))
                        if (!recovered.success) return@withTimeout finish(RuntimeOutcome.INTERNAL_ERROR, recovered.detail)
                        if (recovery.action == RecoveryAction.REPLAN) pendingReplanReason = repeatedReason
                        continue
                    }

                    val bindingPreparation = predicateBindings.prepareActionBinding(milestone, action, executionObservation, runId)
                    if (!bindingPreparation.prepared) {
                        val reason = "predicate target binding rejected: ${bindingPreparation.reason}"
                        recordTurn(toolTurns, planned, toolResultJson(false, action, before, before, "ambiguous_target", reason))
                        history += "PRE_TOOL_BLOCKED: $action because $reason"
                        ledger.record(StepTrace(milestone.id, before.observationId, action.toString(), before.observationId, TransitionJudgement.NO_PROGRESS, reason))
                        val recovery = recoveryPolicy.decide(
                            RecoveryContext(
                                expectedPackage = expectedRecoveryPackage(milestone),
                                currentPackage = before.packageName,
                                currentMilestoneId = milestone.id,
                                currentMilestoneKind = milestone.kind,
                                failedAction = action,
                                reason = if (bindingPreparation.reason.contains("ambiguous", true)) RecoveryReason.AMBIGUOUS_TARGET else RecoveryReason.TARGET_MISSING,
                            ),
                        )
                        val recovered = executeRecovery(recovery, step, before, lockedPackage, executionHistory, expectedRecoveryPackage(milestone), milestone, action, packagePolicy, launchablePackages, goalContext)
                        traceStore.event(runId, "RECOVERY", mapOf("reason" to recovery.reason.name, "action" to recovery.action.name, "result" to recovered.detail))
                        if (!recovered.success) return@withTimeout finish(RuntimeOutcome.INTERNAL_ERROR, recovered.detail)
                        if (recovery.action == RecoveryAction.REPLAN) pendingReplanReason = reason
                        continue
                    }
                    provisionalBindings = bindingPreparation.provisional

                    if (guarded.shortCircuit != null || action is AgentAction.BindPredicate) {
                        if (!predicateBindings.commitAll(provisionalBindings)) {
                            predicateBindings.rollbackAll(provisionalBindings)
                            continue
                        }
                        // Observation-only binding and its immediate local proof must
                        // refer to the same fresh snapshot and run.
                        val localEvidence = MilestoneEvaluator.evaluate(
                            milestone,
                            plan,
                            executionObservation,
                            lockedPackage,
                            predicateBindings,
                            runId = runId,
                        )
                        val execution = guarded.shortCircuit ?: ActionExecutionResult(true, "bound", "predicate target bound without side effect")
                        evidenceCounters.successfulObservationActions += 1
                        if (execution.status == "already_satisfied" || localEvidence.proven) {
                            evidenceCounters.deterministicEvidenceCount += 1
                        }
                        val resultText = if (localEvidence.proven) "evidence: ${localEvidence.details.joinToString(" | ")}" else execution.detail
                        val feedback = toolResultJson(true, action, before, before, execution.status, resultText)
                        recordTurn(toolTurns, planned, feedback)
                        history += "TOOL_RESULT: $feedback"
                        executionHistory.record(ActionRecord(step, action, true, before.observationId, before.observationId, resultText))
                        ledger.record(StepTrace(milestone.id, before.observationId, action.toString(), before.observationId,
                            if (localEvidence.proven) TransitionJudgement.MILESTONE_COMPLETE else TransitionJudgement.PROGRESS,
                            resultText))
                        if (localEvidence.proven) {
                            evidenceCounters.verifiedMilestones += 1
                            predicateBindings.markVerified(milestone.id)
                            history += "MILESTONE_PROVEN: ${ledger.advance(resultText)}"
                            recoveryPolicy.resetFailures()
                        }
                        traceStore.event(runId, "PREDICATE_BOUND", mapOf("milestone" to milestone.id, "actionType" to TraceSanitizer.actionType(action), "status" to execution.status, "evidence" to resultText))
                        continue
                    }

                    onPhase(step, "Acting")
                    onAction(describeAction(action))
                    onLog("Tool: ${describeAction(action)}")
                    val execution = service.executeDetailed(action, executionObservation)
                    if (!execution.success) {
                        predicateBindings.rollbackAll(provisionalBindings)
                        val reason = execution.detail.ifBlank { "platform rejected the tool or exact readback failed" }
                        val trace = StepTrace(milestone.id, before.observationId, action.toString(), before.observationId, TransitionJudgement.NO_PROGRESS, reason)
                        ledger.record(trace)
                        val feedback = toolResultJson(false, action, before, before, execution.status, reason)
                        recordTurn(toolTurns, planned, feedback)
                        history += "TOOL_RESULT: $feedback"
                        executionHistory.record(ActionRecord(step, action, false, before.observationId, before.observationId, "${execution.status}: $reason"))
                        traceStore.event(runId, "TOOL_RESULT", mapOf("action" to TraceSanitizer.action(action), "actionType" to TraceSanitizer.actionType(action), "status" to "FAILED", "reasonCode" to TraceSanitizer.reasonCode(reason), "before" to before.observationId))
                        val recovery = recoveryPolicy.decide(
                            RecoveryContext(
                                expectedPackage = expectedRecoveryPackage(milestone),
                                currentPackage = before.packageName,
                                currentMilestoneId = milestone.id,
                                currentMilestoneKind = milestone.kind,
                                failedAction = action,
                                reason = classifyRecoveryReason(action, execution.status),
                            ),
                        )
                        val recovered = executeRecovery(recovery, step, before, lockedPackage, executionHistory, expectedRecoveryPackage(milestone), milestone, action, packagePolicy, launchablePackages, goalContext)
                        traceStore.event(runId, "RECOVERY", mapOf("reason" to recovery.reason.name, "action" to recovery.action.name, "result" to recovered.detail))
                        if (!recovered.success) return@withTimeout finish(RuntimeOutcome.INTERNAL_ERROR, recovered.detail)
                        if (recovery.action == RecoveryAction.REPLAN) pendingReplanReason = reason
                        continue
                    }

                    if (!predicateBindings.markDispatched(provisionalBindings) || !predicateBindings.commitDispatched(provisionalBindings)) {
                        predicateBindings.rollbackAll(provisionalBindings)
                        return@withTimeout finish(RuntimeOutcome.INTERNAL_ERROR, "Predicate binding commit failed after successful action")
                    }
                    guard.recordDispatch(action)
                    ledger.recordDispatch(action, executionObservation)
                    if (action !is AgentAction.Wait && action !is AgentAction.BindPredicate) {
                        evidenceCounters.successfulMutatingActions += 1
                    }
                    if (action is AgentAction.LaunchApp && lockedPackage == null) {
                        lockedPackage = action.packageName
                        packagePolicy = packagePolicy.copy(
                            allowedPackages = (packagePolicy.allowedPackages + action.packageName).toMutableSet(),
                            primaryPackage = action.packageName,
                        )
                        targetHint = apps.firstOrNull { it.packageName == lockedPackage }?.label ?: targetHint
                        guard = ToolGuard(plan, packagePolicy)
                    }

                    val rawAfter = awaitStableObservation(rawBefore, action)
                    if (lastWaitTimedOut) {
                        val reason = "screen did not settle after ${TraceSanitizer.action(action)}"
                        val recovery = recoveryPolicy.decide(
                            RecoveryContext(
                                expectedPackage = expectedRecoveryPackage(milestone),
                                currentPackage = rawAfter.packageName,
                                currentMilestoneId = milestone.id,
                                currentMilestoneKind = milestone.kind,
                                reason = RecoveryReason.APP_NOT_RESPONDING,
                                failedAction = action,
                            ),
                        )
                        val recovered = executeRecovery(recovery, step, rawAfter, lockedPackage, executionHistory, expectedRecoveryPackage(milestone), milestone, action, packagePolicy, launchablePackages, goalContext)
                        traceStore.event(runId, "RECOVERY", mapOf("reason" to recovery.reason.name, "action" to recovery.action.name, "result" to recovered.detail))
                        if (!recovered.success) return@withTimeout finish(RuntimeOutcome.ACCESSIBILITY_DISCONNECTED, reason)
                        if (recovery.action == RecoveryAction.REPLAN) pendingReplanReason = reason
                        continue
                    }
                    val postPackageFailure = packageBoundaryFailure(rawAfter, lockedPackage, packagePolicy)
                    if (postPackageFailure != null) {
                        traceStore.event(runId, "PACKAGE_BOUNDARY_BLOCKED", mapOf("reason" to postPackageFailure, "package" to rawAfter.packageName))
                        val recovery = recoveryPolicy.decide(
                            RecoveryContext(
                                expectedPackage = expectedRecoveryPackage(milestone),
                                currentPackage = rawAfter.packageName,
                                currentMilestoneId = milestone.id,
                                currentMilestoneKind = milestone.kind,
                                reason = RecoveryReason.WRONG_PACKAGE,
                                failedAction = action,
                            ),
                        )
                        val recovered = executeRecovery(recovery, step, rawAfter, lockedPackage, executionHistory, expectedRecoveryPackage(milestone), milestone, action, packagePolicy, launchablePackages, goalContext)
                        traceStore.event(runId, "RECOVERY", mapOf("reason" to recovery.reason.name, "action" to recovery.action.name, "result" to recovered.detail))
                        if (!recovered.success) return@withTimeout finish(RuntimeOutcome.INTERNAL_ERROR, recovered.detail)
                        if (recovery.action == RecoveryAction.REPLAN) pendingReplanReason = postPackageFailure
                        continue
                    }
                    val afterPrivacy = PrivacyGuard.prepare(rawAfter)
                    if (!afterPrivacy.allowed) {
                        val reason = "Stopped after tool: ${afterPrivacy.reason}"
                        traceStore.event(runId, "PRIVACY_BLOCKED", mapOf("reason" to afterPrivacy.reason, "package" to rawAfter.packageName))
                        return@withTimeout finish(RuntimeOutcome.SAFETY_BLOCKED, reason)
                    }
                    val after = afterPrivacy.observation
                    ledger.observe(after)
                    if (before.packageName != after.packageName) {
                        val fromPackage = before.packageName.ifBlank { "unknown" }
                        val toPackage = after.packageName.ifBlank { "unknown" }
                        history += "PACKAGE_SWITCH: $fromPackage -> $toPackage"
                        traceStore.event(
                            runId,
                            "PACKAGE_SWITCH",
                            mapOf("fromPackage" to fromPackage, "toPackage" to toPackage),
                        )
                    }

                    val deterministicAfter = MilestoneEvaluator.evaluate(milestone, plan, after, lockedPackage, predicateBindings, runId = runId)
                    if (deterministicAfter.proven) {
                        evidenceCounters.deterministicEvidenceCount += 1
                        evidenceCounters.verifiedMilestones += 1
                        predicateBindings.markVerified(milestone.id)
                    }
                    val changed = before.observationId != after.observationId
                    var judgement = when {
                        deterministicAfter.proven -> TransitionJudgement.MILESTONE_COMPLETE
                        changed -> TransitionJudgement.PROGRESS
                        else -> TransitionJudgement.NO_PROGRESS
                    }
                    var evidence = when {
                        deterministicAfter.proven -> deterministicAfter.details.joinToString(" | ")
                        changed -> observationDelta(before, after)
                        else -> "No stable UI state change"
                    }

                    if (judgement == TransitionJudgement.PROGRESS && needsSemanticCritic(milestone, plan, after)) {
                        onPhase(step, "Critiquing")
                        val afterCapture = if (useVision && lockedPackage != null) {
                            captureBoundScreenshot(after, lockedPackage, packagePolicy)
                        } else {
                            ScreenshotCapture()
                        }
                        if (afterCapture.failure != null && afterCapture.fatal) {
                            return@withTimeout finish(RuntimeOutcome.SAFETY_BLOCKED, afterCapture.failure)
                        }
                        val critic = if (afterCapture.failure != null) {
                            CriticResult(TransitionJudgement.PROGRESS, afterCapture.failure)
                        } else try {
                            client.critiqueTransition(
                                actorKey,
                                actorBaseUrl,
                                actorModel,
                                immutableGoal,
                                plan,
                                ledger.currentMilestoneIndex,
                                action.toString(),
                                before,
                                after,
                                screenshot,
                                afterCapture.dataUrl,
                                settings.currentProvider,
                            )
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Throwable) {
                            CriticResult(TransitionJudgement.PROGRESS, "Local state changed; semantic critic unavailable: ${error.message.orEmpty()}")
                        }
                        if (critic.judgement == TransitionJudgement.MILESTONE_COMPLETE && semanticCompletionAllowed(milestone, plan, after, lockedPackage, predicateBindings, runId)) {
                            judgement = TransitionJudgement.MILESTONE_COMPLETE
                            evidence = critic.evidence
                        } else if (critic.judgement == TransitionJudgement.NO_PROGRESS) {
                            judgement = TransitionJudgement.NO_PROGRESS
                            evidence = critic.evidence
                        }
                    }

                    val trace = StepTrace(milestone.id, before.observationId, action.toString(), after.observationId, judgement, evidence)
                    ledger.record(trace)
                    val feedback = toolResultJson(true, action, before, after, judgement.name.lowercase(), evidence)
                    executionHistory.record(
                        ActionRecord(
                            step = step,
                            action = action,
                            success = true,
                            beforeFingerprint = before.observationId,
                            afterFingerprint = after.observationId,
                            result = if (judgement == TransitionJudgement.MILESTONE_COMPLETE) "evidence: $evidence" else evidence,
                        ),
                    )
                    recordTurn(toolTurns, planned, feedback)
                    history += "TOOL_RESULT: $feedback"
                    traceStore.event(
                        runId,
                        "TOOL_RESULT",
                        mapOf(
                            "action" to TraceSanitizer.action(action),
                            "actionType" to TraceSanitizer.actionType(action),
                            "before" to before.observationId,
                            "after" to after.observationId,
                            "judgement" to judgement.name,
                            "evidence" to evidence,
                        ),
                    )
                    onLog("Result: ${translateJudgement(judgement)} 路 ${evidence.take(100)}")
                    if (judgement == TransitionJudgement.MILESTONE_COMPLETE) {
                        history += "MILESTONE_PROVEN: ${ledger.advance(evidence)}"
                        recoveryPolicy.resetFailures()
                        traceStore.event(runId, "MILESTONE_PROVEN", mapOf("id" to milestone.id, "evidence" to evidence, "source" to "postcondition"))
                    } else if (judgement == TransitionJudgement.PROGRESS) {
                        recoveryPolicy.resetFailures()
                    }
                }

                finish(RuntimeOutcome.INTERNAL_ERROR, "24-step tool budget exhausted without verified completion")
        }
        } catch (timeout: TimeoutCancellationException) {
            activeBindings?.rollbackRun(runId)
            finish(RuntimeOutcome.TIMEOUT, "five-minute run deadline exceeded")
        } catch (cancelled: CancellationException) {
            activeBindings?.rollbackRun(runId)
            val cancellationOutcome = cancellationOutcomeProvider() ?: RuntimeOutcome.USER_CANCELLED
            runCatching {
                traceStore.event(runId, "RUN_FINISHED", mapOf("outcome" to cancellationOutcome.name, "reasonCode" to cancellationOutcome.name))
            }
            runCatching { traceStore.finish(runId, "CANCELLED", cancellationOutcome.name) }
            throw cancelled
        } catch (failure: TaskPlanException) {
            activeBindings?.rollbackRun(runId)
            finish(RuntimeOutcome.PERMANENT_PLAN_ERROR, failure.message ?: "Manager plan failed after retries")
        } catch (error: Throwable) {
            activeBindings?.rollbackRun(runId)
            runCatching { traceStore.finish(runId, "FAILED", error.message ?: error::class.simpleName.orEmpty()) }
            throw error
        }
    }

    private suspend fun verifyStopGate(
        step: Int,
        goal: GoalContext,
        observation: Observation,
        history: List<String>,
        plan: TaskPlan,
        ledger: RunLedger,
        useVision: Boolean,
        apiKey: String,
        baseUrl: String,
        model: String,
        evidenceCounters: StopGateEvidenceCounters,
        lockedPackage: String?,
        packagePolicy: PackagePolicy,
        predicateBindings: PredicateBindingStore? = null,
        runId: String? = null,
    ): VerificationResult {
        onPhase(step, "Verifying")
        ledger.currentMilestone?.let {
            return VerificationResult(false, "Milestone ${it.id} is not proven yet: ${it.objective}")
        }
        if (!evidenceCounters.hasLocalEvidence()) {
            return VerificationResult(false, "No deterministic local predicate evidence exists")
        }
        // Once every milestone is locally proven, a verifier claim is only
        // auxiliary. This also allows an already-satisfied task to finish
        // without inventing a mutating action.
        if (evidenceCounters.deterministicEvidenceCount > 0) {
            return VerificationResult(true, "All milestones proven with local evidence (${evidenceCounters.deterministicEvidenceCount} deterministic checks)")
        }
        val screenshotCapture = if (useVision && lockedPackage != null) {
            captureBoundScreenshot(observation, lockedPackage, packagePolicy)
        } else {
            ScreenshotCapture()
        }
        if (screenshotCapture.failure != null) return VerificationResult(false, screenshotCapture.failure)
        val screenshot = screenshotCapture.dataUrl
        val verification = try {
            client.verifyCompletion(
                apiKey,
                baseUrl,
                model,
                goal,
                observation,
                (history + executionHistory.promptLines()).takeLast(24),
                screenshot,
                plan,
                ledger.evidenceSummary(),
                settings.currentProvider,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            return VerificationResult(false, "Verifier unavailable: ${error.message.orEmpty()}")
        }
        if (!verification.done) return verification

        val localEvidence = LocalCompletionEvaluator.evaluate(goal, executionHistory.all(), null, observation)
        if (!localEvidence.completed && ledger.evidenceSummary() == "No milestone evidence recorded") {
            return VerificationResult(false, "Model completion claim lacks local observable evidence")
        }

        val finalMilestone = plan.milestones.lastOrNull()
        val hasToggleContract = finalMilestone?.successPredicates?.any { it.kind == UiPredicateKind.TOGGLE_STATE } == true
        if (hasToggleContract) {
            val local = MilestoneEvaluator.evaluate(finalMilestone!!, plan, observation, lockedPackage, predicateBindings, runId = runId)
            if (!local.proven) return VerificationResult(false, "Toggle completion lacks a deterministic ON state")
        }
        return verification
    }

    private suspend fun replan(
        oldPlan: TaskPlan,
        ledger: RunLedger,
        goal: GoalContext,
        appCatalog: String,
        targetHint: String,
        apiKey: String,
        baseUrl: String,
        model: String,
        verifierGap: String = "",
    ): TaskPlan = client.createTaskPlan(
        apiKey,
        baseUrl,
        model,
        goal,
        appCatalog,
        targetHint,
        "Previous plan:\n${oldPlan.compactText(ledger.currentMilestoneIndex)}\n" +
            "Failed strategies:\n${ledger.recentFailureContext()}\n" +
        "Verifier gap:\n${verifierGap.ifBlank { "none" }}",
        settings.currentProvider,
    )

    private suspend fun awaitStableObservation(before: Observation, action: AgentAction): Observation {
        lastWaitTimedOut = false
        if (action is AgentAction.Wait) {
            return (WaitEngine.waitForDuration(action.milliseconds, service::observe) as WaitResult.Satisfied).value
        }
        val result = when (action) {
            is AgentAction.LaunchApp -> WaitEngine.waitForPackage(
                packageName = action.packageName,
                timeoutMillis = LAUNCH_SETTLE_MS,
                pollMillis = STABILITY_POLL_MS,
                observe = service::observe,
            )
            else -> WaitEngine.waitForScreenStable(
                timeoutMillis = MAX_SETTLE_MS,
                pollMillis = STABILITY_POLL_MS,
                requiredSamples = REQUIRED_STABLE_SAMPLES,
                observe = service::observe,
            )
        }
        return when (result) {
            is WaitResult.Satisfied -> result.value
            is WaitResult.TimedOut -> {
                lastWaitTimedOut = true
                service.observe().also { onLog("Wait timeout: ${result.reason}") }
            }
        }.takeIf { it.observationId != before.observationId } ?: service.observe()
    }

    private suspend fun captureBoundScreenshot(expected: Observation, lockedPackage: String, packagePolicy: PackagePolicy): ScreenshotCapture {
        fun validate(raw: Observation, phase: String): ScreenshotCapture? {
            packageBoundaryFailure(raw, lockedPackage, packagePolicy)?.let {
                return ScreenshotCapture(failure = "$phase: $it", fatal = true)
            }
            val privacy = PrivacyGuard.prepare(raw)
            if (!privacy.allowed) {
                return ScreenshotCapture(
                    failure = "$phase: screenshot blocked by ${privacy.reason}",
                    fatal = true,
                )
            }
            if (privacy.observation.observationId != expected.observationId) {
                return ScreenshotCapture(failure = "$phase: screen changed during screenshot binding")
            }
            return null
        }

        validate(observeWithPackage(lockedPackage), "before capture")?.let { return it }
        val dataUrl = service.captureScreenDataUrl(expected)
        validate(observeWithPackage(lockedPackage), "after capture")?.let { return it }
        return ScreenshotCapture(dataUrl = dataUrl)
    }

    private suspend fun observeWithPackage(lockedPackage: String?): Observation {
        var observation = service.observe()
        if (lockedPackage == null || observation.packageName.isNotBlank()) return observation
        repeat(PACKAGE_OBSERVATION_RETRIES) {
            delay(PACKAGE_OBSERVATION_RETRY_MS)
            observation = service.observe()
            if (observation.packageName.isNotBlank()) return observation
        }
        return observation
    }

    private fun ensureLaunchMilestone(plan: TaskPlan, targetPackage: String?): TaskPlan {
        if (targetPackage.isNullOrBlank() || plan.milestones.any { it.kind == TaskMilestoneKind.LAUNCH_APP }) return plan
        val launch = TaskMilestone(
            id = "launch",
            objective = "Launch the requested target app",
            successPredicates = listOf(
                UiPredicate(
                    kind = UiPredicateKind.PACKAGE_FOREGROUND,
                    targetPackage = targetPackage,
                    description = "The target app is foreground",
                    predicateId = TaskPlanValidator.predicateIdFor("launch", 0),
                ),
            ),
            kind = TaskMilestoneKind.LAUNCH_APP,
        )
        return plan.copy(milestones = listOf(launch) + plan.milestones)
    }

    private fun prepareRevisedPlan(
        revised: TaskPlan,
        previous: TaskPlan,
        completedCount: Int,
        @Suppress("UNUSED_PARAMETER") targetPackage: String?,
    ): Pair<TaskPlan, Int> {
        val completedIds = previous.milestones.take(completedCount).mapTo(mutableSetOf()) { it.id }
        val prepared = ensureLaunchMilestone(revised.preserveCompletedPrefix(previous, completedCount), targetPackage)
        // A replan may contain a different launch milestone for a secondary app.
        // Only the explicitly proven prefix may be skipped; package presence alone
        // is not proof that a new launch contract was completed.
        val resumeAt = prepared.milestones.takeWhile { milestone -> milestone.id in completedIds }.size
        return prepared to resumeAt
    }

    private fun packageBoundaryFailure(observation: Observation, lockedPackage: String?, packagePolicy: PackagePolicy): String? {
        if (lockedPackage.isNullOrBlank()) return null
        if (observation.packageName.isBlank()) return "Foreground package is unavailable inside the locked task boundary"
        return if (observation.packageName == lockedPackage || packagePolicy.allows(observation.packageName)) {
            null
        } else {
            "Package left the locked task boundary: ${observation.packageName}"
        }
    }

    private fun expectedPackage(
        milestone: TaskMilestone?,
        primaryPackage: String?,
        bindings: PredicateBindingStore? = null,
    ): String? {
        val explicit = milestone?.successPredicates
            ?.firstOrNull { it.kind == UiPredicateKind.PACKAGE_FOREGROUND }
            ?.let { it.targetPackage ?: it.target?.packageName }
        if (!explicit.isNullOrBlank()) return explicit
        val boundPackage = milestone?.successPredicates?.indices
            ?.asSequence()
            ?.mapNotNull { index -> bindings?.get(milestone.id, index)?.boundPackage }
            ?.firstOrNull { it.isNotBlank() }
        return boundPackage ?: primaryPackage
    }

    private fun validatePlanPackages(plan: TaskPlan, launchablePackages: Set<String>) {
        val explicitTargets = plan.milestones.flatMap { milestone ->
            milestone.successPredicates
                .filter { it.kind == UiPredicateKind.PACKAGE_FOREGROUND }
                .mapNotNull { it.targetPackage ?: it.target?.packageName }
        }.toSet()
        val requested = plan.allowedPackages + explicitTargets
        val invalid = requested.filter { it !in launchablePackages || PackagePolicy.isProtectedPackage(it) }
        if (invalid.isNotEmpty()) {
            throw TaskPlanException("Plan references unavailable or protected packages: ${invalid.joinToString(",")}")
        }
    }

    private fun validateRevisedPlan(previous: TaskPlan, revised: TaskPlan, launchablePackages: Set<String>) {
        TaskPlanValidator.requireCompatiblePredicateIds(previous, revised)
        validatePlanPackages(revised, launchablePackages)
    }

    private fun mergePlanPackages(
        current: PackagePolicy,
        plan: TaskPlan,
        launchablePackages: Set<String>,
    ): PackagePolicy {
        val predicateTargets = plan.milestones.flatMap { milestone ->
            milestone.successPredicates
                .filter { it.kind == UiPredicateKind.PACKAGE_FOREGROUND }
                .mapNotNull { it.targetPackage ?: it.target?.packageName }
        }.toSet()
        val requested = plan.allowedPackages + predicateTargets
        return current.copy(
            allowedPackages = PackagePolicy.mergeAllowedPackages(current.allowedPackages, requested, launchablePackages).toMutableSet(),
        )
    }

    private fun needsSemanticCritic(
        milestone: TaskMilestone,
        @Suppress("UNUSED_PARAMETER") plan: TaskPlan,
        @Suppress("UNUSED_PARAMETER") observation: Observation,
    ): Boolean = milestone.successPredicates.any { it.kind == UiPredicateKind.SEMANTIC_CLAIM }

    private fun semanticCompletionAllowed(
        milestone: TaskMilestone,
        plan: TaskPlan,
        observation: Observation,
        targetPackage: String?,
        predicateBindings: PredicateBindingStore? = null,
        runId: String? = null,
    ): Boolean {
        val hasHardPredicate = milestone.successPredicates.any {
            it.kind in setOf(
                UiPredicateKind.PACKAGE_FOREGROUND,
                UiPredicateKind.TEXT_PRESENT,
                UiPredicateKind.EDITABLE_EQUALS,
                UiPredicateKind.IME_HIDDEN,
                UiPredicateKind.ELEMENT_PRESENT,
                UiPredicateKind.ELEMENT_DISAPPEARED,
                UiPredicateKind.ELEMENT_ENABLED,
                UiPredicateKind.ELEMENT_SELECTED,
                UiPredicateKind.ELEMENT_CHECKED,
                UiPredicateKind.ELEMENT_TEXT_EQUALS,
                UiPredicateKind.TOGGLE_STATE,
                UiPredicateKind.TOGGLE_ON,
            )
        }
        if (!hasHardPredicate) return false
        val hardPredicatesProven = MilestoneEvaluator.evaluateHardPredicates(
            milestone,
            plan,
            observation,
            targetPackage,
            predicateBindings,
            runId,
        ).proven
        return hardPredicatesProven
    }

    private fun recordTurn(turns: MutableList<PlannerTurn>, planned: PlannedAction?, resultJson: String) {
        if (planned == null) return
        turns += PlannerTurn(
            callId = planned.callId,
            argumentsJson = planned.argumentsJson,
            resultJson = resultJson,
            reasoningContent = planned.reasoningContent,
            assistantContent = planned.assistantContent,
            native = planned.native,
        )
        while (turns.size > MAX_STORED_TOOL_TURNS) turns.removeAt(0)
    }

    private fun toolResultJson(
        ok: Boolean,
        action: AgentAction,
        before: Observation,
        after: Observation,
        status: String,
        detail: String,
    ): String = JSONObject()
        .put("ok", ok)
        .put("status", status)
        .put("action", describeAction(action))
        .put("beforeObservationId", before.observationId)
        .put("afterObservationId", after.observationId)
        .put("changed", before.observationId != after.observationId)
        .put("package", after.packageName)
        .put("detail", detail.take(800))
        .toString()

    private fun observationDelta(before: Observation, after: Observation): String {
        return TraceSanitizer.observationDelta(before, after)
    }

    private fun describeAction(action: AgentAction): String = when (action) {
        is AgentAction.LaunchApp -> "launch_app(${action.packageName})"
        is AgentAction.ClickText -> "click_text(${action.text.take(40)})"
        is AgentAction.ClickNode -> "click_node(#${action.nodeId})"
        is AgentAction.TapPoint -> "tap_point(${action.x},${action.y})"
        is AgentAction.Swipe -> "swipe(${action.direction})"
        is AgentAction.InputText -> "input_text(#${action.nodeId ?: 0}, ${action.text.length} chars)"
        is AgentAction.SubmitInput -> "submit_input(#${action.nodeId ?: 0})"
        is AgentAction.EnsureToggle -> "ensure_toggle(#${action.nodeId}, ${action.desired})"
        is AgentAction.BindPredicate -> "bind_predicate(${action.predicateId})"
        is AgentAction.Wait -> "wait(${action.milliseconds}ms)"
        is AgentAction.Finish -> "finish"
        is AgentAction.Fail -> "fail"
        AgentAction.Back -> "back"
        AgentAction.Home -> "home"
    }

    private fun actionTarget(action: AgentAction, observation: Observation): String = when (action) {
        else -> TraceSanitizer.actionTarget(action, observation)
    }

    private fun resolveTarget(configured: String, goal: String, apps: List<Pair<String, String>>): String? {
        if (configured.isNotBlank()) {
            return apps.firstOrNull { it.second.equals(configured, true) }?.second
                ?: apps.firstOrNull { it.first.equals(configured, true) }?.second
                ?: apps.firstOrNull { configured.contains(it.first, true) }?.second
        }
        return apps.firstOrNull { (label, _) -> label.isNotBlank() && goal.contains(label, true) }?.second
    }

    private fun isFatalModelError(error: Throwable): Boolean {
        val message = error.message.orEmpty().lowercase()
        return listOf("http 400", "http 401", "http 403", "invalid api key", "base url").any(message::contains)
    }

    private fun translateJudgement(judgement: TransitionJudgement): String = when (judgement) {
        TransitionJudgement.NO_PROGRESS -> "no progress"
        TransitionJudgement.PROGRESS -> "progress"
        TransitionJudgement.MILESTONE_COMPLETE -> "milestone complete"
    }

    private suspend fun executeRecovery(
        decision: RecoveryDecision,
        step: Int,
        before: Observation,
        lockedPackage: String?,
        history: ExecutionHistory,
        expectedPackage: String? = null,
        currentMilestone: TaskMilestone? = null,
        failedAction: AgentAction? = null,
        packagePolicy: PackagePolicy,
        launchablePackages: Set<String>,
        goal: GoalContext,
    ): RecoveryExecution {
        val result = when (decision.action) {
            RecoveryAction.REOBSERVE -> RecoveryExecution(true, service.observe(), "re-observed current screen")
            RecoveryAction.WAIT -> if (decision.reason == RecoveryReason.NETWORK_ERROR) {
                delay(recoveryPolicy.networkBackoffMillis(decision.failureCount))
                RecoveryExecution(true, service.observe(), "network backoff completed")
            } else {
                when (val waited = WaitEngine.waitForScreenStable(
                    timeoutMillis = RECOVERY_WAIT_MS,
                    pollMillis = RECOVERY_POLL_MS,
                    requiredSamples = 2,
                    observe = service::observe,
                )) {
                    is WaitResult.Satisfied -> RecoveryExecution(true, waited.value, "screen became stable")
                    is WaitResult.TimedOut -> RecoveryExecution(false, service.observe(), "recovery wait timed out: ${waited.reason}")
                }
            }
            RecoveryAction.BACK, RecoveryAction.DISMISS -> {
                val success = service.execute(AgentAction.Back, before)
                RecoveryExecution(success, service.observe(), if (success) "back/dismiss executed" else "back/dismiss failed")
            }
            RecoveryAction.RELAUNCH -> {
                val target = expectedPackage ?: lockedPackage
                if (target.isNullOrBlank()) {
                    RecoveryExecution(false, service.observe(), "relaunch requested without a locked package")
                } else {
                    val launch = AgentAction.LaunchApp(target)
                    val safetyFailure = SafetyGuard.validate(launch, before, packagePolicy, launchablePackages, goal).exceptionOrNull()
                    if (safetyFailure != null) {
                        RecoveryExecution(false, service.observe(), "relaunch blocked by local policy")
                    } else {
                        val success = service.execute(launch, before)
                        RecoveryExecution(success, service.observe(), if (success) "relaunch executed" else "relaunch failed")
                    }
                }
            }
            RecoveryAction.REPLAN -> RecoveryExecution(true, service.observe(), "replan requested")
            RecoveryAction.ABORT -> RecoveryExecution(false, service.observe(), decision.detail)
        }
        history.recordRecovery(
            RecoveryRecord(
                step = step,
                reason = decision.reason,
                action = decision.action,
                success = result.success,
                result = result.detail,
            ),
        )
        return result
    }

    private fun classifyRecoveryReason(action: AgentAction, detail: String): RecoveryReason {
        val normalized = detail.lowercase()
        return when {
            normalized.contains("ambiguous") -> RecoveryReason.AMBIGUOUS_TARGET
            normalized.contains("missing") || normalized.contains("selector") -> RecoveryReason.TARGET_MISSING
            action is AgentAction.InputText || action is AgentAction.SubmitInput || normalized.contains("input") || normalized.contains("text_") -> RecoveryReason.INPUT_FAILED
            normalized.contains("package") -> RecoveryReason.WRONG_PACKAGE
            normalized.contains("network") || normalized.contains("http ") || normalized.contains("timeout") -> RecoveryReason.NETWORK_ERROR
            action is AgentAction.LaunchApp -> RecoveryReason.APP_NOT_RESPONDING
            else -> RecoveryReason.TARGET_MISSING
        }
    }

    private companion object {
        const val MAX_TOOL_CALLS = 24
        const val MAX_REPLANS = 2
        const val MAX_NO_PROGRESS = 3
        const val MAX_MODEL_FAILURES = 2
        const val MAX_MODEL_TOOL_TURNS = 10
        const val MAX_STORED_TOOL_TURNS = 14
        const val RUN_TIMEOUT_MS = 5 * 60 * 1_000L
        const val MIN_SETTLE_MS = 350L
        const val STABILITY_POLL_MS = 250L
        const val MAX_SETTLE_MS = 3_000L
        const val LAUNCH_SETTLE_MS = 12_000L
        const val REQUIRED_STABLE_SAMPLES = 2
        const val PACKAGE_OBSERVATION_RETRIES = 4
        const val PACKAGE_OBSERVATION_RETRY_MS = 250L
        const val RECOVERY_WAIT_MS = 1_500L
        const val RECOVERY_POLL_MS = 200L
    }
}
