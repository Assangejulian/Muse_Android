package com.androidagent.app.agent

import android.content.Context
import com.androidagent.app.accessibility.AgentAccessibilityService
import com.androidagent.app.apps.AppCatalog
import com.androidagent.app.data.SecureSettings
import com.androidagent.app.network.DeepSeekClient
import com.androidagent.app.network.PlannedAction
import com.androidagent.app.network.PlannerTurn
import com.androidagent.app.privileged.PrivilegedDeviceBackend
import com.androidagent.app.privileged.PrivilegedBackendRouter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

internal const val DEVICE_ACTION_TURN_LIMIT = 50

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

/**
 * When the Manager already emitted a LAUNCH_APP milestone, canonicalize its
 * success contract to PACKAGE_FOREGROUND. Do not inject a synthetic launch step —
 * route selection (terminal vs accessibility, launch timing, dismiss dialogs
 * first, …) belongs to the model Actor.
 */
internal fun normalizePrimaryLaunchContract(plan: TaskPlan, targetPackage: String?): TaskPlan {
    val packageName = targetPackage?.trim().orEmpty()
    if (packageName.isBlank() || plan.milestones.isEmpty()) return plan

    fun packagePredicate(milestoneId: String, predicateId: String? = null) = UiPredicate(
        kind = UiPredicateKind.PACKAGE_FOREGROUND,
        targetPackage = packageName,
        description = "The primary target app is foreground",
        predicateId = predicateId ?: TaskPlanValidator.predicateIdFor(milestoneId, 0),
    )

    val first = plan.milestones.first()
    if (first.kind != TaskMilestoneKind.LAUNCH_APP) return plan

    val existingPackagePredicate = first.successPredicates.firstOrNull {
        it.kind == UiPredicateKind.PACKAGE_FOREGROUND &&
            (it.targetPackage ?: it.target?.packageName) == packageName
    }
    val normalized = first.copy(
        successPredicates = listOf(
            packagePredicate(first.id, existingPackagePredicate?.predicateId),
        ),
    )
    return TaskPlanValidator.requireValid(plan.copy(milestones = listOf(normalized) + plan.milestones.drop(1)))
}

/**
 * Catalog-only package resolution. Match installed launcher labels and package
 * ids that literally appear in the configured hint or goal text.
 * Spoken nicknames the label does not contain are left to the Manager (full
 * catalog in the prompt) — never hardcoded alias tables.
 */
internal fun resolveTargetPackage(
    configured: String,
    goal: String,
    apps: List<Pair<String, String>>,
): String? {
    if (configured.isNotBlank()) {
        val direct = apps.firstOrNull { it.second.equals(configured, true) }?.second
            ?: apps.firstOrNull { it.first.equals(configured, true) }?.second
            ?: apps.firstOrNull { it.first.isNotBlank() && configured.contains(it.first, true) }?.second
        if (direct != null) return direct
    }
    apps.firstOrNull { (label, _) -> label.isNotBlank() && goal.contains(label, true) }?.second?.let { return it }
    apps.firstOrNull { (_, packageName) ->
        packageName.isNotBlank() && goal.contains(packageName, true)
    }?.second?.let { return it }
    val combined = "$configured $goal"
    return apps.firstOrNull { (label, packageName) ->
        (label.isNotBlank() && combined.contains(label, true)) ||
            (packageName.isNotBlank() && combined.contains(packageName, true))
    }?.second
}

internal fun classifyOperationalFailure(reason: String): RuntimeOutcome {
    val normalized = reason.lowercase()
    return when {
        normalized.contains("accessibility") || normalized.contains("service disconnected") ->
            RuntimeOutcome.ACCESSIBILITY_DISCONNECTED
        normalized.contains("network") || normalized.contains("http ") ->
            RuntimeOutcome.TRANSIENT_NETWORK_ERROR
        else -> RuntimeOutcome.PERMANENT_PLAN_ERROR
    }
}

class AgentRuntime(
    private val context: Context,
    private val settings: SecureSettings,
    private val service: AgentAccessibilityService,
    private val onPhase: (step: Int, phase: String) -> Unit,
    private val onLog: (String) -> Unit,
    private val onAction: (String) -> Unit = {},
    private val onActionCount: (Int) -> Unit = {},
    private val goalOverride: String? = null,
    private val runIdOverride: String? = null,
    private val cancellationOutcomeProvider: () -> RuntimeOutcome? = { null },
) {
    private val client = DeepSeekClient()
    private val executionHistory = ExecutionHistory()
    private val recoveryPolicy = RecoveryPolicy()
    private var cachedOcrFingerprint: String? = null
    private var cachedOcrText: String = ""
    private var cachedOcrAtMillis: Long = 0L

    suspend fun run(): RuntimeResult {
        val immutableGoal = goalOverride?.trim().takeUnless { it.isNullOrBlank() } ?: settings.taskGoal
        val apiKey = settings.apiKey
        val apps = AppCatalog(context.applicationContext).list()
        val launchablePackages = apps.mapTo(mutableSetOf()) { it.packageName }
        val appCatalog = apps.joinToString("\n") { "${it.label} | ${it.packageName}" }.take(16_000)
        var lockedPackage = resolveTargetPackage(settings.targetPackage, immutableGoal, apps.map { it.label to it.packageName })
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
        var activeSideEffects: RunScopedSideEffectLedger? = null
        var activePreDispatchSnapshots: PreDispatchEvidenceStore? = null

        fun finish(outcome: RuntimeOutcome, reason: String): RuntimeResult {
            traceStore.event(
                runId,
                "RUN_FINISHED",
                mapOf("outcome" to outcome.name, "reasonCode" to outcome.name),
            )
            activeBindings?.rollbackRun(runId)
            activeSideEffects?.clear()
            activePreDispatchSnapshots?.clear()
            traceStore.finish(
                runId,
                if (outcome == RuntimeOutcome.SUCCESS) "SUCCEEDED" else "FAILED",
                "${outcome.name}: $reason",
            )
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

                val plan = TaskPlanParser.advisory(goalContext, lockedPackage ?: targetHint)

                val ledger = RunLedger(plan)
                val predicateBindings = PredicateBindingStore()
                activeBindings = predicateBindings
                val sideEffects = RunScopedSideEffectLedger(runId)
                val preDispatchSnapshots = PreDispatchEvidenceStore()
                activeSideEffects = sideEffects
                activePreDispatchSnapshots = preDispatchSnapshots
                fun expectedRecoveryPackage(milestone: TaskMilestone?): String? =
                    expectedPackage(milestone, lockedPackage, predicateBindings)
                var guard = ToolGuard(plan, packagePolicy)
                val history = mutableListOf("ADVISORY_GOAL:\n${plan.compactText(0)}")
                val toolTurns = mutableListOf<PlannerTurn>()
                var modelFailures = 0
                var effectiveActions = 0
                val evidenceCounters = StopGateEvidenceCounters()

                traceStore.event(runId, "GOAL_READY", mapOf("goal" to plan.compactText(0)))
                onLog("Goal ready; Actor owns the live execution route")

                for (step in 1..MAX_CONTROL_CYCLES) {
                    if (effectiveActions >= DEVICE_ACTION_TURN_LIMIT) {
                        return@withTimeout finish(
                            RuntimeOutcome.PERMANENT_PLAN_ERROR,
                            "device-action budget exhausted without verified completion",
                        )
                    }
                    onPhase(step, "Observing")
                    onAction("")
                    val rawBefore = observeWithPackage(lockedPackage)
                    // The Actor chooses the next step directly from this fresh
                    // observation. No speculative Manager plan sits in front of it.
                    val packageFailure = packageBoundaryFailure(rawBefore, lockedPackage, packagePolicy)
                    if (packageFailure != null) {
                        history += "PACKAGE_BOUNDARY: $packageFailure; choose Back, launch_app, wait, finish, or fail"
                        traceStore.event(
                            runId,
                            "PACKAGE_BOUNDARY",
                            mapOf("reason" to packageFailure, "package" to rawBefore.packageName),
                        )
                    }

                    val privacy = if (lockedPackage == null) {
                        // Until a target is selected, expose only the goal and installed app catalog to the model.
                        PrivacyDecision(allowed = true, observation = Observation("", emptyList()))
                    } else {
                        PrivacyGuard.prepare(rawBefore)
                    }
                    if (!privacy.allowed) {
                        val reason = "Privacy degraded before model access: ${privacy.reason}"
                        traceStore.event(runId, "PRIVACY_DEGRADED", mapOf("reason" to privacy.reason, "package" to rawBefore.packageName, "phase" to "before"))
                        history += "PRIVACY_BOUNDARY: ${privacy.reason}; only recovery or termination actions are allowed"
                        onLog(reason)
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
                            "complete" to before.isComplete,
                            "nodeCount" to before.nodes.size,
                            "collectionIssues" to before.collectionIssues,
                            "imeVisible" to before.imeVisible,
                            "windowCount" to before.windowIds.size,
                            "ocrPresent" to before.ocrText.isNotBlank(),
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
                            evidenceCounters,
                            lockedPackage,
                            packagePolicy,
                        )
                        if (verified.done) {
                            return@withTimeout finish(RuntimeOutcome.SUCCESS, verified.reason)
                        }
                        return@withTimeout finish(RuntimeOutcome.PERMANENT_PLAN_ERROR, verified.reason)
                    }

                    val deterministicBefore = MilestoneEvaluator.evaluate(
                        milestone,
                        plan,
                        before,
                        lockedPackage,
                        predicateBindings,
                        runId = runId,
                        preDispatchSnapshots = preDispatchSnapshots,
                    )
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
                        // Tell the Actor it is looping, but never skip planning.
                        // Skipping here used to spin Observing forever at 01/50.
                        val reason = if (cycle != null) "ABAB_LOOP" else "SCREEN_UNCHANGED"
                        history += "STUCK_SIGNAL: $reason (noProgress=${ledger.noProgressCount}); choose a genuinely different live action"
                    }

                    onPhase(step, "Planning")
                    val screenshotCapture = if (useVision && lockedPackage != null && packageFailure == null && privacy.allowed) {
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
                    val screenshotFingerprint = screenshot?.let(TraceSanitizer::digest)
                    // Always ask the model (or Shizuku-aware planner). Local recipes
                    // no longer hijack the action path.
                    var planned: PlannedAction? = null
                    val proposed = try {
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
                            harnessState = "ADVISORY GOAL: ${milestone.objective}\n" +
                                "loopDetected=${ledger.cyclePeriod() != null || ledger.noProgressCount >= 2}\n" +
                                "avoidReopening=${sideEffects.exploredActivationLabels().takeLast(8).joinToString(" | ").ifBlank { "none" }}\n" +
                                "terminalReady=${PrivilegedBackendRouter.isReady()}",
                            toolTurns = toolTurns.takeLast(MAX_MODEL_TOOL_TURNS),
                            provider = settings.currentProvider,
                            primaryPackage = packagePolicy.primaryPackage,
                            currentPackage = before.packageName,
                            allowedPackages = packagePolicy.allowedPackages,
                            terminalAvailable = PrivilegedBackendRouter.isReady(),
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
                        history += "MODEL_RETRY: $reason"
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
                    modelFailures = 0
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
                        )
                        if (verification.done) {
                            recordTurn(toolTurns, planned, toolResultJson(true, proposed, before, before, "verified_complete", verification.reason))
                            return@withTimeout finish(RuntimeOutcome.SUCCESS, verification.reason)
                        }
                        val feedback = toolResultJson(false, proposed, before, before, "finish_rejected", verification.reason)
                        recordTurn(toolTurns, planned, feedback)
                        history += "STOP_GATE_REJECTED: ${verification.reason}"
                        ledger.record(StepTrace(milestone.id, before.observationId, proposed.toString(), before.observationId, TransitionJudgement.NO_PROGRESS, verification.reason))
                        onLog("Completion not yet proven: ${verification.reason.take(120)}")
                        continue
                    }

                    if (proposed is AgentAction.Fail) {
                        val feedback = toolResultJson(false, proposed, before, before, "actor_blocked", proposed.reason)
                        recordTurn(toolTurns, planned, feedback)
                        history += "ACTOR_BLOCKED: ${proposed.reason}"
                        return@withTimeout finish(RuntimeOutcome.PERMANENT_PLAN_ERROR, proposed.reason)
                    }


                    // Planning may take time. The shared engine always receives
                    // a fresh execution snapshot and owns the full preflight.
                    val executionObservation = observeWithPackage(lockedPackage, includeOcr = false)

                    if (proposed is AgentAction.Terminal && !PrivilegedBackendRouter.isReady()) {
                        val reason = "Shizuku terminal disconnected before dispatch; replan with accessibility actions"
                        recordTurn(toolTurns, planned, toolResultJson(false, proposed, before, before, "terminal_unavailable", reason))
                        history += "PRE_TOOL_BLOCKED: $reason"
                        continue
                    }

                    if (lockedPackage == null && proposed !is AgentAction.LaunchApp) {
                        val reason = "launch_app must select one installed target before any screen-dependent tool"
                        recordTurn(toolTurns, planned, toolResultJson(false, proposed, before, before, "target_required", reason))
                        history += "PRE_TOOL_BLOCKED: $proposed because $reason"
                        ledger.record(StepTrace(milestone.id, before.observationId, TraceSanitizer.action(proposed), before.observationId, TransitionJudgement.NO_PROGRESS, reason))
                        history += "TARGET_FEEDBACK: $reason"
                        continue
                    }

                    if (proposed is AgentAction.TapPoint && screenshot == null) {
                        val reason = "tap_point requires an explicitly enabled current screenshot"
                        recordTurn(toolTurns, planned, toolResultJson(false, proposed, before, before, "policy_rejected", reason))
                        history += "PRE_TOOL_BLOCKED: $reason"
                        ledger.record(StepTrace(milestone.id, before.observationId, TraceSanitizer.action(proposed), before.observationId, TransitionJudgement.NO_PROGRESS, reason))
                        continue
                    }

                    var dispatchedByDriver = false
                    val stepEngine = RuntimeStepEngine(object : RuntimeStepDriver {
                        override suspend fun executeDetailed(
                            action: AgentAction,
                            observation: Observation,
                        ): ActionExecutionResult = service.executeDetailed(action, observation)

                        override suspend fun executeDetailed(
                            action: AgentAction,
                            observation: Observation,
                            resolvedTarget: ResolvedActionTarget?,
                        ): ActionExecutionResult {
                            dispatchedByDriver = true
                            onPhase(step, "Acting")
                            onAction(describeAction(action, resolvedTarget?.semanticNode))
                            onLog("Tool: ${describeAction(action, resolvedTarget?.semanticNode)}")
                            return service.executeDetailed(action, observation, resolvedTarget)
                        }

                        override suspend fun settle(
                            before: Observation,
                            action: AgentAction,
                        ): RuntimeStepSettleResult = awaitStableObservationDetailed(before, action)

                    })
                    val engineResult = stepEngine.execute(
                        RuntimeStepRequest(
                            step = step,
                            proposed = proposed,
                            planningObservation = rawBefore,
                            executionObservation = executionObservation,
                            plan = plan,
                            milestone = milestone,
                            guard = guard,
                            ledger = ledger,
                            bindings = predicateBindings,
                            recoveryPolicy = recoveryPolicy,
                            packagePolicy = packagePolicy,
                            launchablePackages = launchablePackages,
                            goal = goalContext,
                            targetPackage = expectedRecoveryPackage(milestone),
                            evidenceCounters = evidenceCounters,
                            runId = runId,
                            sideEffects = sideEffects,
                            preDispatchSnapshots = preDispatchSnapshots,
                            screenshotFingerprint = screenshotFingerprint,
                        ),
                    )
                    if (engineResult.execution != null) {
                        effectiveActions += 1
                        onActionCount(effectiveActions)
                        if (!dispatchedByDriver) {
                            engineResult.action?.let {
                                onAction(describeAction(it, engineResult.resolvedTarget?.semanticNode))
                            }
                        }
                    }
                    val stepAction = engineResult.action ?: proposed

                    engineResult.recoveryDecisions.forEach { decision ->
                        traceStore.event(
                            runId,
                            "RECOVERY",
                            mapOf("reason" to decision.reason.name, "action" to decision.action.name),
                        )
                    }
                    if (stepAction is AgentAction.LaunchApp &&
                        engineResult.status !in setOf(
                            RuntimeStepStatus.STALE,
                            RuntimeStepStatus.BLOCKED,
                            RuntimeStepStatus.EXECUTION_FAILED,
                            RuntimeStepStatus.ABORTED,
                        )
                    ) {
                        lockedPackage = stepAction.packageName
                        packagePolicy = packagePolicy.copy(
                            allowedPackages = (packagePolicy.allowedPackages + stepAction.packageName).toMutableSet(),
                            primaryPackage = stepAction.packageName,
                        )
                        targetHint = apps.firstOrNull { it.packageName == lockedPackage }?.label ?: targetHint
                        guard = ToolGuard(plan, packagePolicy)
                    }

                    if (engineResult.status == RuntimeStepStatus.ABORTED) {
                        val fatal = engineResult.reason.contains("accessibility", true) ||
                            engineResult.reason.contains("SAFETY", true) ||
                            engineResult.reason.contains("side effect identity", true)
                        if (fatal) {
                            return@withTimeout finish(classifyOperationalFailure(engineResult.reason), engineResult.reason)
                        }
                        history += "TOOL_ABORTED: ${engineResult.reason}; choose another action from the live state"
                        continue
                    }
                    if (engineResult.needsReplan) {
                        history += "TOOL_FEEDBACK: ${engineResult.reason}; choose another action from the live state"
                        continue
                    }
                    if (engineResult.status in setOf(
                            RuntimeStepStatus.STALE,
                            RuntimeStepStatus.BLOCKED,
                            RuntimeStepStatus.EXECUTION_FAILED,
                            RuntimeStepStatus.RESULT_UNKNOWN,
                        )
                    ) {
                        val status = engineResult.execution?.status ?: engineResult.status.name.lowercase()
                        val feedback = toolResultJson(false, stepAction, before, before, status, engineResult.reason)
                        recordTurn(toolTurns, planned, feedback)
                        history += "TOOL_RESULT: $feedback"
                        executionHistory.record(
                            ActionRecord(
                                step,
                                stepAction,
                                false,
                                before.observationId,
                                engineResult.after.observationId,
                                engineResult.reason,
                                summary = describeAction(stepAction, engineResult.resolvedTarget?.semanticNode),
                            ),
                        )
                        traceStore.event(
                            runId,
                            "TOOL_RESULT",
                            mapOf(
                                "action" to TraceSanitizer.action(stepAction),
                                "actionType" to TraceSanitizer.actionType(stepAction),
                                "status" to engineResult.status.name,
                                "reasonCode" to TraceSanitizer.reasonCode(engineResult.reason),
                                "before" to before.observationId,
                                "effectiveTargetKey" to engineResult.resolvedTarget?.effectiveActionNode
                                    ?.let(TargetResolver::crossWindowStructureKey),
                                "targetPackage" to engineResult.resolvedTarget?.targetPackage,
                                "targetWindowId" to engineResult.resolvedTarget?.targetWindowId,
                                "dispatchMode" to engineResult.resolvedTarget?.dispatchMode?.name,
                                "inputGeneration" to engineResult.inputGeneration,
                            ),
                        )
                        continue
                    }

                    val rawAfter = enrichWithLocalOcr(engineResult.after, lockedPackage)
                    val postPackageFailure = packageBoundaryFailure(rawAfter, lockedPackage, packagePolicy)
                    if (postPackageFailure != null) {
                        traceStore.event(runId, "PACKAGE_BOUNDARY", mapOf("reason" to postPackageFailure, "package" to rawAfter.packageName))
                        history += "PACKAGE_BOUNDARY: $postPackageFailure; Actor chooses the next safe action"
                    }
                    val afterPrivacy = PrivacyGuard.prepare(rawAfter)
                    if (!afterPrivacy.allowed) {
                        val reason = "Privacy degraded after tool: ${afterPrivacy.reason}"
                        traceStore.event(runId, "PRIVACY_DEGRADED", mapOf("reason" to afterPrivacy.reason, "package" to rawAfter.packageName, "phase" to "after"))
                        history += "PRIVACY_BOUNDARY: ${afterPrivacy.reason}; Actor chooses the next safe action"
                        onLog(reason)
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

                    val engineJudgement = when (engineResult.status) {
                        RuntimeStepStatus.MILESTONE_COMPLETE -> TransitionJudgement.MILESTONE_COMPLETE
                        RuntimeStepStatus.PROGRESS, RuntimeStepStatus.OBSERVATION_ONLY -> TransitionJudgement.PROGRESS
                        else -> TransitionJudgement.NO_PROGRESS
                    }
                    val judgement = engineJudgement
                    val evidence = if (stepAction is AgentAction.Terminal) {
                        engineResult.execution?.detail.orEmpty().ifBlank { engineResult.reason }
                    } else {
                        engineResult.reason
                    }

                    if (judgement != engineJudgement) {
                        ledger.record(StepTrace(milestone.id, before.observationId, TraceSanitizer.action(stepAction), after.observationId, judgement, evidence))
                    }
                    val feedback = toolResultJson(true, stepAction, before, after, judgement.name.lowercase(), evidence)
                    executionHistory.record(
                        ActionRecord(
                            step = step,
                            action = stepAction,
                            success = true,
                            beforeFingerprint = before.observationId,
                            afterFingerprint = after.observationId,
                            result = if (judgement == TransitionJudgement.MILESTONE_COMPLETE) "evidence: $evidence" else evidence,
                            summary = describeAction(stepAction, engineResult.resolvedTarget?.semanticNode),
                        ),
                    )
                    recordTurn(toolTurns, planned, feedback)
                    history += "TOOL_RESULT: $feedback"
                    traceStore.event(
                        runId,
                        "TOOL_RESULT",
                        mapOf(
                            "action" to TraceSanitizer.action(stepAction),
                            "actionType" to TraceSanitizer.actionType(stepAction),
                            "before" to before.observationId,
                            "after" to after.observationId,
                            "judgement" to judgement.name,
                            "evidence" to evidence,
                            "effectiveTargetKey" to engineResult.resolvedTarget?.effectiveActionNode
                                ?.let(TargetResolver::crossWindowStructureKey),
                            "targetPackage" to engineResult.resolvedTarget?.targetPackage,
                            "targetWindowId" to engineResult.resolvedTarget?.targetWindowId,
                            "dispatchMode" to engineResult.resolvedTarget?.dispatchMode?.name,
                            "inputGeneration" to engineResult.inputGeneration,
                        ),
                    )
                    onLog("Result: ${translateJudgement(judgement)} 路 ${evidence.take(100)}")
                    if (judgement == TransitionJudgement.MILESTONE_COMPLETE) {
                        if (!engineResult.completed) {
                            evidenceCounters.deterministicEvidenceCount += 1
                            evidenceCounters.verifiedMilestones += 1
                            predicateBindings.markVerified(milestone.id)
                            history += "MILESTONE_PROVEN: ${ledger.advance(evidence)}"
                        } else {
                            history += "MILESTONE_PROVEN: ${milestone.id} proven: $evidence"
                        }
                        recoveryPolicy.resetFailures()
                        traceStore.event(runId, "MILESTONE_PROVEN", mapOf("id" to milestone.id, "evidence" to evidence, "source" to "postcondition"))
                    } else if (judgement == TransitionJudgement.PROGRESS) {
                        recoveryPolicy.resetFailures()
                    }
                }

                finish(RuntimeOutcome.PERMANENT_PLAN_ERROR, "control-cycle budget exhausted without verified completion")
        }
        } catch (timeout: TimeoutCancellationException) {
            activeBindings?.rollbackRun(runId)
            activeSideEffects?.clear()
            activePreDispatchSnapshots?.clear()
            finish(RuntimeOutcome.TIMEOUT, "twenty-minute run deadline exceeded")
        } catch (cancelled: CancellationException) {
            activeBindings?.rollbackRun(runId)
            activeSideEffects?.clear()
            activePreDispatchSnapshots?.clear()
            val cancellationOutcome = cancellationOutcomeProvider() ?: RuntimeOutcome.USER_CANCELLED
            runCatching {
                traceStore.event(runId, "RUN_FINISHED", mapOf("outcome" to cancellationOutcome.name, "reasonCode" to cancellationOutcome.name))
            }
            runCatching { traceStore.finish(runId, "CANCELLED", cancellationOutcome.name) }
            throw cancelled
        } catch (failure: TaskPlanException) {
            activeBindings?.rollbackRun(runId)
            activeSideEffects?.clear()
            activePreDispatchSnapshots?.clear()
            finish(RuntimeOutcome.PERMANENT_PLAN_ERROR, failure.message ?: "Runtime contract error")
        } catch (error: Throwable) {
            activeBindings?.rollbackRun(runId)
            activeSideEffects?.clear()
            activePreDispatchSnapshots?.clear()
            runCatching {
                traceStore.finish(
                    runId,
                    "FAILED",
                    "${RuntimeOutcome.INTERNAL_ERROR.name}: ${error.message ?: error::class.simpleName.orEmpty()}",
                )
            }
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
    ): VerificationResult {
        onPhase(step, "Verifying")
        if (ledger.complete && evidenceCounters.hasLocalEvidence()) {
            return VerificationResult(true, "Goal completed with local observable evidence")
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
        return verification
    }

    private suspend fun awaitStableObservationDetailed(
        before: Observation,
        action: AgentAction,
    ): RuntimeStepSettleResult {
        if (action is AgentAction.Wait) {
            val observation = (WaitEngine.waitForDuration(action.milliseconds, service::observe) as WaitResult.Satisfied).value
            return RuntimeStepSettleResult(DispatchResultState.CONFIRMED, observation, "wait duration completed")
        }
        if (action is AgentAction.Terminal) {
            return RuntimeStepSettleResult(
                DispatchResultState.CONFIRMED,
                service.observe(),
                "terminal command returned a definitive exit status",
            )
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
                // Structure fingerprint ignores feed/video text churn so settle
                // does not spin until timeout on animated pages.
                observe = service::observe,
                stabilityKey = { it.structureFingerprint() },
            )
        }
        return when (result) {
            is WaitResult.Satisfied -> RuntimeStepSettleResult(
                DispatchResultState.CONFIRMED,
                result.value,
                "screen settle condition satisfied",
            )
            is WaitResult.TimedOut -> {
                // Dynamic screens may never become visually still. Preserve the
                // latest observation without claiming that dispatch was confirmed;
                // the outer Actor loop can choose a different route without a
                // nested recovery burn loop.
                onLog("Settle timeout; continuing with an uncertain result: ${result.reason}")
                RuntimeStepSettleResult(
                    DispatchResultState.RESULT_UNKNOWN,
                    service.observe(),
                    "settle timed out on a dynamic screen; using latest observation",
                )
            }
        }
    }

    private suspend fun captureBoundScreenshot(expected: Observation, lockedPackage: String, packagePolicy: PackagePolicy): ScreenshotCapture {
        fun validate(raw: Observation, phase: String): ScreenshotCapture? {
            packageBoundaryFailure(raw, lockedPackage, packagePolicy)?.let {
                return ScreenshotCapture(failure = "$phase: $it", fatal = true)
            }
            val privacy = PrivacyGuard.prepare(raw)
            if (!privacy.allowed) {
                // Do not hard-stop the run on a sensitive transient page during screenshot binding.
                return ScreenshotCapture(failure = "$phase: screenshot blocked by ${privacy.reason}")
            }
            if (privacy.observation.observationId != expected.observationId) {
                return ScreenshotCapture(failure = "$phase: screen changed during screenshot binding")
            }
            return null
        }

        validate(observeWithPackage(lockedPackage, includeOcr = false), "before capture")?.let { return it }
        val dataUrl = service.captureScreenDataUrl(expected)
        validate(observeWithPackage(lockedPackage, includeOcr = false), "after capture")?.let { return it }
        return ScreenshotCapture(dataUrl = dataUrl)
    }

    private suspend fun observeWithPackage(lockedPackage: String?, includeOcr: Boolean = true): Observation {
        var observation = service.observe()
        if (lockedPackage != null && observation.packageName.isBlank()) {
            repeat(PACKAGE_OBSERVATION_RETRIES) {
                delay(PACKAGE_OBSERVATION_RETRY_MS)
                observation = service.observe()
                if (observation.packageName.isNotBlank()) {
                    return if (includeOcr) enrichWithLocalOcr(observation, lockedPackage) else observation
                }
            }
        }
        if (observation.packageName.isBlank() && settings.privilegedBackendEnabled) {
            val privilegedPackage = PrivilegedDeviceBackend.foregroundPackage()
            if (!privilegedPackage.isNullOrBlank()) {
                observation = observation.copy(packageName = privilegedPackage)
            }
        }
        return if (includeOcr) enrichWithLocalOcr(observation, lockedPackage) else observation
    }

    /**
     * Accessibility trees from video, canvas, and custom-view apps can expose
     * controls without useful text. Local OCR is a privacy-preserving read-only
     * fallback; it is never turned into an unverified clickable node.
     */
    private suspend fun enrichWithLocalOcr(observation: Observation, lockedPackage: String?): Observation {
        if (lockedPackage.isNullOrBlank() || observation.packageName != lockedPackage) return observation
        if (!LocalOcrPolicy.shouldEnrich(observation)) return observation
        val fingerprint = observation.observationId
        val now = System.currentTimeMillis()
        if (cachedOcrFingerprint == fingerprint && now - cachedOcrAtMillis <= OCR_CACHE_MILLIS) {
            return observation.copy(ocrText = cachedOcrText)
        }
        val ocr = runCatching { service.recognizeScreenText() }.getOrDefault("")
            .lineSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
            .take(MAX_OCR_LINES)
            .joinToString("\n")
            .take(MAX_OCR_CHARS)
        cachedOcrFingerprint = fingerprint
        cachedOcrText = ocr
        cachedOcrAtMillis = System.currentTimeMillis()
        if (ocr.isBlank()) return observation
        return observation.copy(ocrText = ocr)
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
        .put("detail", detail.take(if (action is AgentAction.Terminal) 8_000 else 800))
        .toString()

    private fun observationDelta(before: Observation, after: Observation): String {
        return TraceSanitizer.observationDelta(before, after)
    }

    private fun describeAction(action: AgentAction, target: UiNodeSnapshot? = null): String =
        ActorActionLabel.describe(action, target)

    private fun actionTarget(action: AgentAction, observation: Observation): String = when (action) {
        else -> TraceSanitizer.actionTarget(action, observation)
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
        const val MAX_CONTROL_CYCLES = 200
        const val MAX_NO_PROGRESS = 6
        const val MAX_MODEL_FAILURES = 5
        const val MAX_MODEL_TOOL_TURNS = 16
        const val MAX_STORED_TOOL_TURNS = 20
        const val RUN_TIMEOUT_MS = 20 * 60 * 1_000L
        const val STABILITY_POLL_MS = 250L
        const val MAX_SETTLE_MS = 2_500L
        const val LAUNCH_SETTLE_MS = 12_000L
        const val REQUIRED_STABLE_SAMPLES = 2
        const val PACKAGE_OBSERVATION_RETRIES = 4
        const val PACKAGE_OBSERVATION_RETRY_MS = 250L
        const val MAX_OCR_LINES = 80
        const val MAX_OCR_CHARS = 4_000
        const val OCR_CACHE_MILLIS = 1_000L
    }
}
