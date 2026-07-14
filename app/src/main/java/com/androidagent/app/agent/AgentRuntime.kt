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

data class RuntimeResult(val succeeded: Boolean, val reason: String)

private data class ScreenshotCapture(
    val dataUrl: String? = null,
    val failure: String? = null,
    val fatal: Boolean = false,
)

class AgentRuntime(
    private val context: Context,
    private val settings: SecureSettings,
    private val service: AgentAccessibilityService,
    private val onPhase: (step: Int, phase: String) -> Unit,
    private val onLog: (String) -> Unit,
    private val onAction: (String) -> Unit = {},
) {
    private val client = DeepSeekClient()
    private val executionHistory = ExecutionHistory()
    private val recoveryPolicy = RecoveryPolicy()

    suspend fun run(): RuntimeResult {
        val immutableGoal = settings.taskGoal
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
        val runId = traceStore.startRun(immutableGoal, actorModel)

        fun finish(succeeded: Boolean, reason: String): RuntimeResult {
            traceStore.finish(runId, if (succeeded) "SUCCEEDED" else "FAILED", reason)
            return RuntimeResult(succeeded, reason)
        }

        return try {
            withTimeout(RUN_TIMEOUT_MS) {
                onPhase(0, "Compiling")
                if (settings.visionEnabled && !useVision) {
                    onLog("Vision is enabled but no vision API key is configured; using node-only mode")
                }

                var plan = client.createTaskPlan(
                    apiKey,
                    settings.modelBaseUrl,
                    settings.modelName,
                    goalContext,
                    appCatalog,
                    targetHint,
                    provider = settings.currentProvider,
                )
                if (lockedPackage == null) {
                    lockedPackage = resolveTarget(plan.targetAppHint, immutableGoal, apps.map { it.label to it.packageName })
                    targetHint = apps.firstOrNull { it.packageName == lockedPackage }?.label ?: targetHint
                    if (lockedPackage != null) {
                        packagePolicy = packagePolicy.copy(
                            allowedPackages = mutableSetOf(lockedPackage!!),
                            primaryPackage = lockedPackage,
                        )
                    }
                }
                packagePolicy = packagePolicy.copy(
                    allowedPackages = (packagePolicy.allowedPackages + plan.allowedPackages).toMutableSet(),
                )
                plan = ensureLaunchMilestone(plan, lockedPackage)

                var ledger = RunLedger(plan)
                var guard = ToolGuard(plan, lockedPackage)
                val history = mutableListOf("MANAGER_PLAN:\n${plan.compactText(0)}")
                val toolTurns = mutableListOf<PlannerTurn>()
                var replans = 0
                var modelFailures = 0
                var successfulToolCalls = 0
                var finishRejections = 0

                traceStore.event(runId, "PLAN_CREATED", mapOf("plan" to plan.compactText(0)))
                onLog("Plan ready: ${plan.milestones.size} milestones")

                for (step in 1..MAX_TOOL_CALLS) {
                    onPhase(step, "Observing")
                    onAction("")
                    val rawBefore = observeWithPackage(lockedPackage)
                    if (step == 1 && !lockedPackage.isNullOrBlank() && rawBefore.packageName != lockedPackage) {
                        val launch = AgentAction.LaunchApp(lockedPackage!!)
                        onPhase(step, "Acting")
                        onAction(describeAction(launch))
                        if (!service.execute(launch, rawBefore)) {
                            return@withTimeout finish(false, "Unable to launch the locked target app")
                        }
                        val launched = awaitStableObservation(rawBefore, launch)
                        if (launched.packageName != lockedPackage) {
                            return@withTimeout finish(false, "Target app did not become ready before the launch deadline")
                        }
                        successfulToolCalls += 1
                        history += "LOCAL_TOOL_RESULT: launched locked package $lockedPackage without exposing the previous app"
                        continue
                    }
                    val packageFailure = packageBoundaryFailure(rawBefore, lockedPackage, packagePolicy)
                    if (packageFailure != null) return@withTimeout finish(false, packageFailure)

                    val privacy = if (lockedPackage == null) {
                        // Until a target is selected, expose only the goal and installed app catalog to the model.
                        PrivacyDecision(allowed = true, observation = Observation("", emptyList()))
                    } else {
                        PrivacyGuard.prepare(rawBefore)
                    }
                    if (!privacy.allowed) {
                        val reason = "Stopped before model access: ${privacy.reason}"
                        traceStore.event(runId, "PRIVACY_BLOCKED", mapOf("reason" to privacy.reason, "package" to rawBefore.packageName))
                        return@withTimeout finish(false, reason)
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
                            successfulToolCalls,
                            lockedPackage,
                            packagePolicy,
                        )
                        if (verified.done) {
                            return@withTimeout finish(true, verified.reason)
                        }
                        if (replans >= MAX_REPLANS) return@withTimeout finish(false, verified.reason)
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
                        val completed = ledger.currentMilestoneIndex
                        val prepared = prepareRevisedPlan(revised, plan, completed, lockedPackage)
                        plan = prepared.first
                        packagePolicy = packagePolicy.copy(allowedPackages = (packagePolicy.allowedPackages + plan.allowedPackages).toMutableSet())
                        ledger.replacePlan(plan, prepared.second)
                        guard = ToolGuard(plan, lockedPackage)
                        replans += 1
                        history += "REPLAN_AFTER_STOP_GATE: ${verified.reason}"
                        continue
                    }

                    val deterministicBefore = MilestoneEvaluator.evaluate(milestone, plan, before, lockedPackage)
                    if (deterministicBefore.proven) {
                        val proof = deterministicBefore.details.joinToString(" | ")
                        history += "MILESTONE_PROVEN: ${ledger.advance(proof)}"
                        traceStore.event(runId, "MILESTONE_PROVEN", mapOf("id" to milestone.id, "evidence" to proof, "source" to "deterministic"))
                        onLog("Milestone ${milestone.id} verified locally")
                        continue
                    }

                    val cycle = ledger.cyclePeriod()
                    if (ledger.noProgressCount >= MAX_NO_PROGRESS || (cycle != null && ledger.noProgressCount >= 2)) {
                        val recovery = recoveryPolicy.decide(
                            if (cycle != null) RecoveryReason.ABAB_LOOP else RecoveryReason.SCREEN_UNCHANGED,
                            milestone.id,
                        )
                        history += "RECOVERY: ${recovery.reason} -> ${recovery.action} (${recovery.detail})"
                        if (recovery.action == RecoveryAction.ABORT) {
                            return@withTimeout finish(false, recovery.detail)
                        }
                        if (replans >= MAX_REPLANS) {
                            return@withTimeout finish(false, "strategy budget exhausted after repeated no-progress results")
                        }
                        onPhase(step, "Replanning")
                        val revised = replan(
                            plan,
                            ledger,
                            goalContext,
                            appCatalog,
                            targetHint,
                            apiKey,
                            settings.modelBaseUrl,
                            settings.modelName,
                        )
                        val completed = ledger.currentMilestoneIndex
                        val prepared = prepareRevisedPlan(revised, plan, completed, lockedPackage)
                        plan = prepared.first
                        packagePolicy = packagePolicy.copy(allowedPackages = (packagePolicy.allowedPackages + plan.allowedPackages).toMutableSet())
                        ledger.replacePlan(plan, prepared.second)
                        guard = ToolGuard(plan, lockedPackage)
                        replans += 1
                        history += "REPLAN: failed strategies must not be repeated\n${ledger.recentFailureContext()}"
                        traceStore.event(runId, "REPLAN", mapOf("reason" to "no progress", "plan" to plan.compactText(completed)))
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
                        if (screenshotCapture.fatal) return@withTimeout finish(false, reason)
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
                                    "SUCCESS CONTRACT: ${milestone.successEvidence}\n${ledger.planText()}",
                                toolTurns = toolTurns.takeLast(MAX_MODEL_TOOL_TURNS),
                                provider = settings.currentProvider,
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
                                return@withTimeout finish(false, reason)
                            }
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
                            "action" to proposed.toString(),
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
                            successfulToolCalls,
                            lockedPackage,
                            packagePolicy,
                        )
                        if (verification.done) {
                            recordTurn(toolTurns, planned, toolResultJson(true, proposed, before, before, "verified_complete", verification.reason))
                            return@withTimeout finish(true, verification.reason)
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
                            val completed = ledger.currentMilestoneIndex
                            val prepared = prepareRevisedPlan(revised, plan, completed, lockedPackage)
                            plan = prepared.first
                            packagePolicy = packagePolicy.copy(allowedPackages = (packagePolicy.allowedPackages + plan.allowedPackages).toMutableSet())
                            ledger.replacePlan(plan, prepared.second)
                            guard = ToolGuard(plan, lockedPackage)
                            replans += 1
                            finishRejections = 0
                        }
                        continue
                    }

                    if (proposed is AgentAction.Fail) {
                        val feedback = toolResultJson(false, proposed, before, before, "actor_blocked", proposed.reason)
                        recordTurn(toolTurns, planned, feedback)
                        history += "ACTOR_BLOCKED: ${proposed.reason}"
                        if (replans >= MAX_REPLANS) return@withTimeout finish(false, proposed.reason)
                        onPhase(step, "Replanning")
                        val revised = replan(plan, ledger, goalContext, appCatalog, targetHint, apiKey, settings.modelBaseUrl, settings.modelName, proposed.reason)
                        val completed = ledger.currentMilestoneIndex
                        val prepared = prepareRevisedPlan(revised, plan, completed, lockedPackage)
                        plan = prepared.first
                        packagePolicy = packagePolicy.copy(allowedPackages = (packagePolicy.allowedPackages + plan.allowedPackages).toMutableSet())
                        ledger.replacePlan(plan, prepared.second)
                        guard = ToolGuard(plan, lockedPackage)
                        replans += 1
                        continue
                    }

                    val guarded = guard.normalizeAndValidate(proposed, before, milestone)
                    val action = guarded.action
                    if (action == null) {
                        val reason = guarded.rejection ?: "pre-tool policy rejected the action"
                        val feedback = toolResultJson(false, proposed, before, before, "policy_rejected", reason)
                        recordTurn(toolTurns, planned, feedback)
                        history += "PRE_TOOL_BLOCKED: $proposed because $reason"
                        ledger.record(StepTrace(milestone.id, before.observationId, proposed.toString(), before.observationId, TransitionJudgement.NO_PROGRESS, reason))
                        traceStore.event(runId, "PRE_TOOL_BLOCKED", mapOf("action" to proposed.toString(), "reason" to reason))
                        onLog("Tool blocked: $reason")
                        continue
                    }

                    if (lockedPackage == null && action !is AgentAction.LaunchApp) {
                        val reason = "launch_app must select one installed target before any screen-dependent tool"
                        recordTurn(toolTurns, planned, toolResultJson(false, action, before, before, "target_required", reason))
                        history += "PRE_TOOL_BLOCKED: $action because $reason"
                        ledger.record(StepTrace(milestone.id, before.observationId, action.toString(), before.observationId, TransitionJudgement.NO_PROGRESS, reason))
                        continue
                    }

                    if (action is AgentAction.TapPoint && screenshot == null) {
                        val reason = "tap_point requires an explicitly enabled current screenshot"
                        recordTurn(toolTurns, planned, toolResultJson(false, action, before, before, "policy_rejected", reason))
                        history += "PRE_TOOL_BLOCKED: $reason"
                        ledger.record(StepTrace(milestone.id, before.observationId, action.toString(), before.observationId, TransitionJudgement.NO_PROGRESS, reason))
                        continue
                    }

                    val repeatedReason = ledger.blockRepeated(action, before)
                    if (repeatedReason != null) {
                        recordTurn(toolTurns, planned, toolResultJson(false, action, before, before, "duplicate_action", repeatedReason))
                        history += "PRE_TOOL_BLOCKED: $action because $repeatedReason"
                        ledger.record(StepTrace(milestone.id, before.observationId, action.toString(), before.observationId, TransitionJudgement.NO_PROGRESS, repeatedReason))
                        onLog("Duplicate strategy blocked")
                        continue
                    }

                    val observationBound = action is AgentAction.ClickText || action is AgentAction.ClickNode ||
                        action is AgentAction.TapPoint || action is AgentAction.InputText ||
                        action is AgentAction.SubmitInput || action is AgentAction.EnsureToggle
                    val executionObservation = if (observationBound) service.observe() else rawBefore
                    if (observationBound && executionObservation.observationId != rawBefore.observationId) {
                        val reason = "screen changed before tool dispatch; re-observe before acting"
                        val current = PrivacyGuard.prepare(executionObservation).observation
                        recordTurn(toolTurns, planned, toolResultJson(false, action, before, current, "stale_observation", reason))
                        history += "PRE_TOOL_BLOCKED: $action because $reason"
                        ledger.observe(current)
                        ledger.record(StepTrace(milestone.id, before.observationId, action.toString(), current.observationId, TransitionJudgement.NO_PROGRESS, reason))
                        continue
                    }

                    val safetyFailure = SafetyGuard.validate(action, before, packagePolicy, launchablePackages).exceptionOrNull()
                    if (safetyFailure != null) {
                        val reason = safetyFailure.message ?: "safety policy rejected the action"
                        recordTurn(toolTurns, planned, toolResultJson(false, action, before, before, "safety_rejected", reason))
                        history += "PRE_TOOL_BLOCKED: $action because $reason"
                        ledger.record(StepTrace(milestone.id, before.observationId, action.toString(), before.observationId, TransitionJudgement.NO_PROGRESS, reason))
                        traceStore.event(runId, "PRE_TOOL_BLOCKED", mapOf("action" to action.toString(), "reason" to reason))
                        onLog("Safety guard blocked: $reason")
                        continue
                    }

                    onPhase(step, "Acting")
                    onAction(describeAction(action))
                    onLog("Tool: ${describeAction(action)}")
                    val dispatched = service.execute(action, rawBefore)
                    if (!dispatched) {
                        val reason = "platform rejected the tool or exact readback failed"
                        val trace = StepTrace(milestone.id, before.observationId, action.toString(), before.observationId, TransitionJudgement.NO_PROGRESS, reason)
                        ledger.record(trace)
                        val feedback = toolResultJson(false, action, before, before, "execution_failed", reason)
                        recordTurn(toolTurns, planned, feedback)
                        history += "TOOL_RESULT: $feedback"
                        executionHistory.record(ActionRecord(step, action, false, before.observationId, before.observationId, reason))
                        traceStore.event(runId, "TOOL_RESULT", mapOf("action" to action.toString(), "status" to "FAILED", "before" to before.observationId))
                        continue
                    }

                    guard.recordDispatch(action)
                    ledger.recordDispatch(action, before)
                    if (action !is AgentAction.Wait) successfulToolCalls += 1
                    if (action is AgentAction.LaunchApp && lockedPackage == null) {
                        lockedPackage = action.packageName
                        packagePolicy = packagePolicy.copy(
                            allowedPackages = mutableSetOf(action.packageName),
                            primaryPackage = action.packageName,
                        )
                        targetHint = apps.firstOrNull { it.packageName == lockedPackage }?.label ?: targetHint
                        guard = ToolGuard(plan, lockedPackage)
                    }

                    val rawAfter = awaitStableObservation(rawBefore, action)
                    val postPackageFailure = packageBoundaryFailure(rawAfter, lockedPackage, packagePolicy)
                    if (postPackageFailure != null) {
                        traceStore.event(runId, "PACKAGE_BOUNDARY_BLOCKED", mapOf("reason" to postPackageFailure, "package" to rawAfter.packageName))
                        return@withTimeout finish(false, postPackageFailure)
                    }
                    val afterPrivacy = PrivacyGuard.prepare(rawAfter)
                    if (!afterPrivacy.allowed) {
                        val reason = "Stopped after tool: ${afterPrivacy.reason}"
                        traceStore.event(runId, "PRIVACY_BLOCKED", mapOf("reason" to afterPrivacy.reason, "package" to rawAfter.packageName))
                        return@withTimeout finish(false, reason)
                    }
                    val after = afterPrivacy.observation
                    ledger.observe(after)

                    val deterministicAfter = MilestoneEvaluator.evaluate(milestone, plan, after, lockedPackage)
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
                            return@withTimeout finish(false, afterCapture.failure)
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
                        if (critic.judgement == TransitionJudgement.MILESTONE_COMPLETE && semanticCompletionAllowed(milestone, plan, after, lockedPackage)) {
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
                            "action" to action.toString(),
                            "before" to before.observationId,
                            "after" to after.observationId,
                            "judgement" to judgement.name,
                            "evidence" to evidence,
                        ),
                    )
                    onLog("Result: ${translateJudgement(judgement)} 路 ${evidence.take(100)}")
                    if (judgement == TransitionJudgement.MILESTONE_COMPLETE) {
                        history += "MILESTONE_PROVEN: ${ledger.advance(evidence)}"
                        traceStore.event(runId, "MILESTONE_PROVEN", mapOf("id" to milestone.id, "evidence" to evidence, "source" to "postcondition"))
                    }
                }

                finish(false, "24-step tool budget exhausted without verified completion")
            }
        } catch (timeout: TimeoutCancellationException) {
            finish(false, "five-minute run deadline exceeded")
        } catch (cancelled: CancellationException) {
            runCatching { traceStore.finish(runId, "CANCELLED", "cancelled by user") }
            throw cancelled
        } catch (error: Throwable) {
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
        successfulToolCalls: Int,
        lockedPackage: String?,
        packagePolicy: PackagePolicy,
    ): VerificationResult {
        onPhase(step, "Verifying")
        ledger.currentMilestone?.let {
            return VerificationResult(false, "Milestone ${it.id} is not proven yet: ${it.objective}")
        }
        if (successfulToolCalls == 0) return VerificationResult(false, "No successful tool result exists")
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
        val hasToggleContract = finalMilestone?.successPredicates?.any { it.kind == UiPredicateKind.TOGGLE_ON } == true
        if (hasToggleContract) {
            val local = MilestoneEvaluator.evaluate(finalMilestone!!, plan, observation, lockedPackage)
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
        val result = when (action) {
            is AgentAction.LaunchApp -> WaitEngine.waitForPackage(
                packageName = action.packageName,
                timeoutMillis = LAUNCH_SETTLE_MS,
                pollMillis = STABILITY_POLL_MS,
                observe = service::observe,
            )
            is AgentAction.Wait -> WaitEngine.waitForScreenStable(
                timeoutMillis = action.milliseconds,
                pollMillis = STABILITY_POLL_MS,
                requiredSamples = REQUIRED_STABLE_SAMPLES,
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
            is WaitResult.TimedOut -> service.observe().also { onLog("Wait timeout: ${result.reason}") }
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
            successPredicates = listOf(UiPredicate(UiPredicateKind.PACKAGE_FOREGROUND, description = "The target app is foreground")),
            kind = TaskMilestoneKind.LAUNCH_APP,
        )
        return plan.copy(milestones = listOf(launch) + plan.milestones)
    }

    private fun prepareRevisedPlan(
        revised: TaskPlan,
        previous: TaskPlan,
        completedCount: Int,
        targetPackage: String?,
    ): Pair<TaskPlan, Int> {
        val completedIds = previous.milestones.take(completedCount).mapTo(mutableSetOf()) { it.id }
        val prepared = ensureLaunchMilestone(revised.preserveCompletedPrefix(previous, completedCount), targetPackage)
        val resumeAt = prepared.milestones.takeWhile { milestone ->
            milestone.id in completedIds || (targetPackage != null && milestone.kind == TaskMilestoneKind.LAUNCH_APP)
        }.size
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
    ): Boolean {
        val hasHardPredicate = milestone.successPredicates.any {
            it.kind in setOf(
                UiPredicateKind.PACKAGE_FOREGROUND,
                UiPredicateKind.TEXT_PRESENT,
                UiPredicateKind.EDITABLE_EQUALS,
                UiPredicateKind.IME_HIDDEN,
                UiPredicateKind.ELEMENT_STATE,
                UiPredicateKind.TOGGLE_ON,
            )
        }
        if (!hasHardPredicate) return false
        val hardPredicatesProven = MilestoneEvaluator.evaluateHardPredicates(
            milestone,
            plan,
            observation,
            targetPackage,
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
        val oldText = before.nodes.asSequence().filter { it.visible && !it.password }.map { "${it.text} ${it.description}".trim() }.filter(String::isNotBlank).toSet()
        val newText = after.nodes.asSequence().filter { it.visible && !it.password }.map { "${it.text} ${it.description}".trim() }.filter(String::isNotBlank).toSet()
        val added = (newText - oldText).take(5)
        val removed = (oldText - newText).take(3)
        return buildString {
            append("UI changed")
            if (before.packageName != after.packageName) append("; package ${before.packageName} -> ${after.packageName}")
            if (added.isNotEmpty()) append("; added=${added.joinToString(" | ")}")
            if (removed.isNotEmpty()) append("; removed=${removed.joinToString(" | ")}")
        }.take(800)
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
        is AgentAction.Wait -> "wait(${action.milliseconds}ms)"
        is AgentAction.Finish -> "finish"
        is AgentAction.Fail -> "fail"
        AgentAction.Back -> "back"
        AgentAction.Home -> "home"
    }

    private fun actionTarget(action: AgentAction, observation: Observation): String = when (action) {
        is AgentAction.ClickNode -> observation.nodes.firstOrNull { it.id == action.nodeId }?.let {
            "#${it.id} text=${it.text.take(80)} description=${it.description.take(80)} viewId=${it.viewId} bounds=${it.bounds}"
        } ?: "missing node #${action.nodeId}"
        is AgentAction.ClickText -> "text=${action.text.take(120)}"
        is AgentAction.InputText -> "editable=#${action.nodeId ?: 0} chars=${action.text.length}"
        is AgentAction.SubmitInput -> "editable=#${action.nodeId ?: 0}"
        is AgentAction.TapPoint -> "normalized=${action.x},${action.y}"
        is AgentAction.EnsureToggle -> "toggle=#${action.nodeId} desired=${action.desired}"
        else -> ""
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
    }
}
