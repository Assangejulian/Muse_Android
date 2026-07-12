package com.androidagent.app.agent

import android.content.Context
import com.androidagent.app.accessibility.AgentAccessibilityService
import com.androidagent.app.apps.AppCatalog
import com.androidagent.app.automation.DailyTaskScheduler
import com.androidagent.app.data.SecureSettings
import com.androidagent.app.network.DeepSeekClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

data class RuntimeResult(val succeeded: Boolean, val reason: String)

class AgentRuntime(
    private val context: Context,
    private val settings: SecureSettings,
    private val service: AgentAccessibilityService,
    private val onPhase: (step: Int, phase: String) -> Unit,
    private val onLog: (String) -> Unit,
) {
    private val client = DeepSeekClient()

    suspend fun run(): RuntimeResult {
        val immutableGoal = settings.taskGoal
        val apiKey = settings.apiKey
        val apps = AppCatalog(context.applicationContext).list()
        val appCatalog = apps.joinToString("\n") { "${it.label} | ${it.packageName}" }.take(16_000)
        var lockedPackage = resolveTarget(settings.targetPackage, immutableGoal, apps.map { it.label to it.packageName })
        val canonicalQuery = GoalContract.extractSearchQuery(immutableGoal)
        val targetHint = apps.firstOrNull { it.packageName == lockedPackage }?.label
            ?: settings.targetPackage.ifBlank { immutableGoal.take(80) }

        val visionKey = settings.visionApiKey.ifBlank { settings.apiKeyFor("qwen") }
        val autoVision = canonicalQuery != null && visionKey.isNotBlank()
        val useVision = visionKey.isNotBlank() && (settings.visionEnabled || autoVision)
        val actorKey = if (useVision) visionKey else apiKey
        val actorBaseUrl = if (useVision) settings.visionBaseUrl else settings.modelBaseUrl
        val actorModel = if (useVision) settings.visionModelName else settings.modelName
        val traceStore = AgentTraceStore(context)
        val runId = traceStore.startRun(immutableGoal, actorModel)
        fun result(succeeded: Boolean, reason: String): RuntimeResult {
            traceStore.finish(runId, if (succeeded) "SUCCEEDED" else "FAILED", reason)
            return RuntimeResult(succeeded, reason)
        }

        try {
            onPhase(0, "Compiling")
        if (autoVision && !settings.visionEnabled) onLog("Vision grounding enabled automatically for this search task")
        var plan = if (canonicalQuery != null) {
            TaskPlanParser.fallback(immutableGoal, targetHint, canonicalQuery)
        } else {
            client.createTaskPlan(
                apiKey,
                settings.modelBaseUrl,
                settings.modelName,
                immutableGoal,
                appCatalog,
                targetHint,
                canonicalQuery,
            )
        }
        var ledger = RunLedger(plan)
        var guard = ToolGuard(plan, lockedPackage)
        traceStore.event(runId, "PLAN_CREATED", mapOf("plan" to plan.compactText(0)))
        val history = mutableListOf<String>()
        history += "MANAGER_PLAN:\n${plan.compactText(0)}"
        onLog("Manager created ${plan.milestones.size} auditable milestones")
        var replans = 0

        for (step in 1..MAX_TOOL_CALLS) {
            onPhase(step, "Observing")
            val before = service.observe()
            ledger.observe(before)
            traceStore.event(runId, "OBSERVATION", mapOf("step" to step, "observationId" to before.observationId, "package" to before.packageName, "milestone" to ledger.currentMilestone?.id))

            val milestone = ledger.currentMilestone
            if (milestone == null) {
                onPhase(step, "Verifying")
                val screenshot = if (useVision) service.captureScreenDataUrl(before) else null
                val verification = client.verifyCompletion(
                    actorKey,
                    actorBaseUrl,
                    actorModel,
                    immutableGoal,
                    before,
                    history,
                    screenshot,
                    plan,
                    ledger.evidenceSummary(),
                )
                if (verification.done) {
                    scheduleNextRunIfNeeded(immutableGoal)
                    traceStore.event(runId, "VERIFICATION", mapOf("result" to "PROVEN", "reason" to verification.reason))
                    return result(true, verification.reason)
                }
                traceStore.event(runId, "VERIFICATION", mapOf("result" to "MISSING", "reason" to verification.reason))
                if (replans >= MAX_REPLANS) return result(false, "completion verifier still found missing evidence")
                onPhase(step, "Replanning")
                val completedCount = plan.repairStartIndex()
                history += "VERIFIER_REJECTED: ${verification.reason}"
                val revised = replan(plan, ledger, immutableGoal, appCatalog, targetHint, canonicalQuery, apiKey, settings.modelBaseUrl, settings.modelName, verification.reason)
                plan = revised.preserveCompletedPrefix(plan, completedCount)
                ledger.replacePlan(plan, completedCount)
                guard = ToolGuard(plan, lockedPackage)
                replans += 1
                history += "REPLAN: verifier rejected completion — ${verification.reason}"
                traceStore.event(runId, "REPLAN", mapOf("reason" to "verifier rejected completion", "plan" to plan.compactText(completedCount)))
                continue
            }

            val deterministic = MilestoneEvaluator.evaluate(milestone, plan, before, lockedPackage)
            if (deterministic.proven) {
                val evidence = deterministic.details.joinToString(" | ")
                history += "MILESTONE_PROVEN: ${ledger.advance(evidence)}"
                traceStore.event(runId, "MILESTONE_PROVEN", mapOf("id" to milestone.id, "evidence" to evidence, "source" to "deterministic"))
                onLog("Milestone ${milestone.id} proven deterministically")
                continue
            }

            val cycle = ledger.cyclePeriod()
            if (cycle != null || ledger.noProgressCount >= MAX_NO_PROGRESS) {
                if (replans >= MAX_REPLANS) return result(false, "strategy budget exhausted after repeated UI cycles")
                history += "STRATEGY_EXHAUSTED: cyclePeriod=$cycle noProgress=${ledger.noProgressCount}"
                onLog("Cortex detected a stalled strategy; requesting a different plan")
                onPhase(step, "Replanning")
                val completedCount = when (milestone.kind) {
                    TaskMilestoneKind.SELECT_ENTITY -> plan.indexOfKind(TaskMilestoneKind.ENTER_QUERY)
                    TaskMilestoneKind.OPEN_CONTENT -> plan.indexOfKind(TaskMilestoneKind.SELECT_ENTITY)
                    TaskMilestoneKind.FINAL_ACTION -> plan.indexOfKind(TaskMilestoneKind.OPEN_CONTENT)
                    else -> ledger.currentMilestoneIndex
                }.takeIf { it >= 0 } ?: ledger.currentMilestoneIndex
                if (completedCount < ledger.currentMilestoneIndex) {
                    history += "RECOVERY_DIRECTIVE: rewind to ${plan.milestones[completedCount].kind}; re-establish evidence without repeating rejected targets"
                }
                val revised = replan(plan, ledger, immutableGoal, appCatalog, targetHint, canonicalQuery, apiKey, settings.modelBaseUrl, settings.modelName)
                plan = revised.preserveCompletedPrefix(plan, completedCount)
                ledger.replacePlan(plan, completedCount)
                guard = ToolGuard(plan, lockedPackage)
                replans += 1
                traceStore.event(runId, "REPLAN", mapOf("reason" to "cycle or no progress", "cyclePeriod" to cycle, "plan" to plan.compactText(completedCount)))
                continue
            }

            onPhase(step, "Planning")
            val screenshot = if (useVision) service.captureScreenDataUrl(before) else null
            val workflowAction = guard.requiredWorkflowAction(before, milestone)
            val proposed = try {
                workflowAction ?: ActionParser.parse(
                    client.plan(
                        apiKey = actorKey,
                        baseUrl = actorBaseUrl,
                        model = actorModel,
                        goal = immutableGoal,
                        allowedPackage = lockedPackage,
                        appCatalog = appCatalog,
                        observation = before,
                        history = history,
                        screenshotDataUrl = screenshot,
                        harnessState = "CURRENT MILESTONE: ${milestone.id} ${milestone.objective}\nSUCCESS CONTRACT: ${milestone.successEvidence}\n${ledger.planText()}",
                    ),
                )
            } catch (error: Throwable) {
                val reason = "invalid Actor tool call: ${error.message.orEmpty()}"
                ledger.record(StepTrace(milestone.id, before.observationId, "invalid_model_output", before.observationId, TransitionJudgement.NO_PROGRESS, reason))
                history += "PRE_TOOL_BLOCKED: $reason"
                traceStore.event(runId, "PRE_TOOL_BLOCKED", mapOf("action" to "invalid_model_output", "reason" to reason))
                continue
            }
            if (workflowAction != null) {
                onLog("Workflow guard forced: $workflowAction")
                traceStore.event(runId, "WORKFLOW_FORCED", mapOf("step" to step, "action" to workflowAction.toString(), "reason" to "locked query and visible keyboard"))
            }
            traceStore.event(runId, "TOOL_PROPOSED", mapOf("step" to step, "milestone" to milestone.id, "action" to proposed.toString(), "target" to actionTarget(proposed, before), "basedOn" to before.observationId))
            val guarded = guard.normalizeAndValidate(proposed, before, milestone)
            val action = guarded.action
            if (action == null) {
                history += "PRE_TOOL_BLOCKED: $proposed because ${guarded.rejection}"
                onLog("Pre-tool hook blocked action: ${guarded.rejection}")
                ledger.record(StepTrace(milestone.id, before.observationId, proposed.toString(), before.observationId, TransitionJudgement.NO_PROGRESS, guarded.rejection.orEmpty()))
                traceStore.event(runId, "PRE_TOOL_BLOCKED", mapOf("action" to proposed.toString(), "reason" to guarded.rejection))
                continue
            }
            if (action is AgentAction.TapPoint && screenshot == null) {
                val reason = "tap_point requires a current vision screenshot"
                history += "PRE_TOOL_BLOCKED: $reason"
                ledger.record(StepTrace(milestone.id, before.observationId, action.toString(), before.observationId, TransitionJudgement.NO_PROGRESS, reason))
                traceStore.event(runId, "PRE_TOOL_BLOCKED", mapOf("action" to action.toString(), "reason" to reason))
                continue
            }
            if (action is AgentAction.Finish) {
                history += "STOP_GATE_BLOCKED: Actor cannot finish; current milestone ${milestone.id} is unproven"
                onLog("Stop gate rejected Actor completion request")
                ledger.record(StepTrace(milestone.id, before.observationId, action.toString(), before.observationId, TransitionJudgement.NO_PROGRESS, "Actor cannot satisfy the Stop Gate"))
                traceStore.event(runId, "STOP_GATE_BLOCKED", mapOf("milestone" to milestone.id))
                continue
            }
            if (action is AgentAction.Fail) {
                history += "ACTOR_BLOCKED: ${action.reason}"
                ledger.record(StepTrace(milestone.id, before.observationId, action.toString(), before.observationId, TransitionJudgement.NO_PROGRESS, action.reason))
                continue
            }
            val repeatedReason = ledger.blockRepeated(action, before)
            if (repeatedReason != null) {
                history += "PRE_TOOL_BLOCKED: $action because $repeatedReason"
                onLog("Pre-tool hook blocked exhausted strategy")
                ledger.record(StepTrace(milestone.id, before.observationId, action.toString(), before.observationId, TransitionJudgement.NO_PROGRESS, repeatedReason))
                traceStore.event(runId, "PRE_TOOL_BLOCKED", mapOf("action" to action.toString(), "reason" to repeatedReason))
                continue
            }
            val observationBoundAction = action is AgentAction.ClickText ||
                action is AgentAction.ClickNode ||
                action is AgentAction.TapPoint ||
                action is AgentAction.InputText ||
                action is AgentAction.SubmitInput ||
                action is AgentAction.EnsureToggle
            val executionObservation = if (observationBoundAction) service.observe() else before
            if (observationBoundAction && executionObservation.observationId != before.observationId) {
                val reason = "stale observation: screen changed before tool dispatch"
                ledger.observe(executionObservation)
                history += "PRE_TOOL_BLOCKED: $action because $reason"
                ledger.record(StepTrace(milestone.id, before.observationId, action.toString(), executionObservation.observationId, TransitionJudgement.NO_PROGRESS, reason))
                traceStore.event(runId, "PRE_TOOL_BLOCKED", mapOf("action" to action.toString(), "reason" to reason, "before" to before.observationId, "current" to executionObservation.observationId))
                continue
            }
            val safetyFailure = SafetyGuard.validate(action, executionObservation, lockedPackage, apps.mapTo(mutableSetOf()) { it.packageName }).exceptionOrNull()
            if (safetyFailure != null) {
                val reason = safetyFailure.message ?: "Safety policy rejected the action"
                history += "PRE_TOOL_BLOCKED: $action because $reason"
                ledger.record(StepTrace(milestone.id, before.observationId, action.toString(), before.observationId, TransitionJudgement.NO_PROGRESS, reason))
                traceStore.event(runId, "PRE_TOOL_BLOCKED", mapOf("action" to action.toString(), "reason" to reason))
                continue
            }

            onPhase(step, "Acting")
            onLog("Tool call: $action basedOn=${before.observationId}")
            val dispatched = service.execute(action, before)
            if (!dispatched) {
                val trace = StepTrace(milestone.id, before.observationId, action.toString(), before.observationId, TransitionJudgement.NO_PROGRESS, "platform rejected or exact text readback failed")
                ledger.record(trace)
                history += "TOOL_RESULT: $trace"
                onLog("Tool failed deterministic execution/readback")
                traceStore.event(runId, "TOOL_RESULT", mapOf("action" to action.toString(), "status" to "FAILED", "before" to before.observationId))
                continue
            }
            guard.recordDispatch(action)
            ledger.recordDispatch(action)
            if (action is AgentAction.LaunchApp && lockedPackage == null) lockedPackage = action.packageName
            delay(if (action is AgentAction.Wait) action.milliseconds else POST_ACTION_SETTLE_MS)
            val after = service.observe()
            ledger.observe(after)

            onPhase(step, "Critiquing")
            val afterScreenshot = if (useVision) service.captureScreenDataUrl(after) else null
            val rawCritic = runCatching {
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
                    afterScreenshot,
                )
            }.getOrElse {
                if (before.stateFingerprint() == after.stateFingerprint()) CriticResult(TransitionJudgement.NO_PROGRESS, "No stable state change")
                else CriticResult(TransitionJudgement.PROGRESS, "Stable state changed; semantic evidence unavailable")
            }
            val deterministicAfter = MilestoneEvaluator.evaluate(milestone, plan, after, lockedPackage)
            val hardAfter = MilestoneEvaluator.evaluateHardPredicates(milestone, plan, after, lockedPackage)
            val togglePredicates = milestone.successPredicates.filter { it.kind == UiPredicateKind.TOGGLE_ON }
            val toggleAfter = if (togglePredicates.isEmpty()) {
                PredicateEvidence(true, listOf("No toggle predicate"))
            } else {
                MilestoneEvaluator.evaluate(milestone.copy(successPredicates = togglePredicates), plan, after, lockedPackage)
            }
            val criticCompletionAllowed = rawCritic.judgement == TransitionJudgement.MILESTONE_COMPLETE &&
                hardAfter.proven &&
                (togglePredicates.isEmpty() || toggleAfter.proven)
            val critic = when {
                deterministicAfter.proven -> CriticResult(TransitionJudgement.MILESTONE_COMPLETE, deterministicAfter.details.joinToString(" | "))
                rawCritic.judgement == TransitionJudgement.MILESTONE_COMPLETE && !criticCompletionAllowed -> {
                    CriticResult(TransitionJudgement.NO_PROGRESS, "Critic completion rejected by hard predicates: ${hardAfter.details.joinToString(" | ")}")
                }
                else -> rawCritic
            }
            val trace = StepTrace(milestone.id, before.observationId, action.toString(), after.observationId, critic.judgement, critic.evidence)
            ledger.record(trace)
            history += "TOOL_RESULT: $trace"
            traceStore.event(runId, "TOOL_RESULT", mapOf("action" to action.toString(), "before" to before.observationId, "after" to after.observationId, "judgement" to critic.judgement.name, "evidence" to critic.evidence))
            onLog("Post-tool critic: ${critic.judgement} — ${critic.evidence.take(120)}")
            if (critic.judgement == TransitionJudgement.MILESTONE_COMPLETE) {
                history += "MILESTONE_PROVEN: ${ledger.advance(critic.evidence)}"
                traceStore.event(runId, "MILESTONE_PROVEN", mapOf("id" to milestone.id, "evidence" to critic.evidence, "source" to "critic"))
            }
        }
            return result(false, "tool-call budget exhausted without verified completion")
        } catch (error: CancellationException) {
            runCatching { traceStore.finish(runId, "CANCELLED", "cancelled by user") }
            throw error
        } catch (error: Throwable) {
            runCatching { traceStore.finish(runId, "FAILED", error.message ?: error::class.simpleName.orEmpty()) }
            throw error
        }
    }

    private suspend fun replan(
        oldPlan: TaskPlan,
        ledger: RunLedger,
        goal: String,
        appCatalog: String,
        targetHint: String,
        canonicalQuery: String?,
        apiKey: String,
        baseUrl: String,
        model: String,
        verifierGap: String = "",
    ): TaskPlan {
        if (canonicalQuery != null) return oldPlan
        return client.createTaskPlan(
            apiKey,
            baseUrl,
            model,
            goal,
            appCatalog,
            targetHint,
            canonicalQuery,
            "Previous plan:\n${oldPlan.compactText(ledger.currentMilestoneIndex)}\nFailures:\n${ledger.recentFailureContext()}\nVerifier gap:\n${verifierGap.ifBlank { "none" }}",
        )
    }

    private suspend fun scheduleNextRunIfNeeded(goal: String) {
        if (goal.contains("金币") || goal.contains("签到") || goal.contains("领取")) {
            val text = service.recognizeScreenText()
            val next = DailyTaskScheduler.scheduleFromOcr(context.applicationContext, goal, text)
            onLog(if (next != null) "Next daily run scheduled from OCR: $next" else "No next-run time found by OCR")
        }
    }

    private fun actionTarget(action: AgentAction, observation: Observation): String = when (action) {
        is AgentAction.ClickNode -> observation.nodes.firstOrNull { it.id == action.nodeId }?.let {
            "#${it.id} text=${it.text.take(80)} description=${it.description.take(80)} viewId=${it.viewId} package=${it.packageName} bounds=${it.bounds} path=${it.treePath}"
        } ?: "missing node #${action.nodeId}"
        is AgentAction.ClickText -> "text=${action.text.take(120)}"
        is AgentAction.InputText -> "editable=#${action.nodeId ?: 0} value=${action.text.take(120)}"
        is AgentAction.SubmitInput -> "editable=#${action.nodeId ?: 0}"
        is AgentAction.TapPoint -> "normalized=${action.x},${action.y}"
        is AgentAction.EnsureToggle -> "toggle=#${action.nodeId} desired=${action.desired}"
        else -> ""
    }

    private fun resolveTarget(configured: String, goal: String, apps: List<Pair<String, String>>): String? {
        if (configured.isNotBlank()) {
            return apps.firstOrNull { it.second.equals(configured, true) }?.second
                ?: apps.firstOrNull { it.first.equals(configured, true) }?.second
        }
        if (goal.contains("B站", true) || goal.contains("哔哩哔哩", true)) {
            apps.firstOrNull { (label, packageName) ->
                packageName.startsWith("tv.danmaku.bili") || label.contains("哔哩哔哩", true)
            }?.let { return it.second }
        }
        return apps.firstOrNull { (label, _) -> goal.contains(label, true) }?.second
    }

    private companion object {
        const val MAX_TOOL_CALLS = 36
        const val MAX_REPLANS = 4
        const val MAX_NO_PROGRESS = 3
        const val POST_ACTION_SETTLE_MS = 1_100L
    }
}
