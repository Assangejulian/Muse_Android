package com.androidagent.app.agent

/** Minimal dependency seam for deterministic Runtime contract tests. */
interface RuntimeHarnessAccessibilityService {
    suspend fun observe(): Observation
    suspend fun executeDetailed(action: AgentAction, observation: Observation): ActionExecutionResult
    suspend fun executeRecovery(action: RecoveryAction, observation: Observation): ActionExecutionResult
}

fun interface RuntimeHarnessPlanner {
    suspend fun nextAction(observation: Observation, milestone: TaskMilestone, history: List<ActionRecord>): AgentAction
}

fun interface RuntimeHarnessClock {
    suspend fun afterAction(before: Observation, action: AgentAction, observe: suspend () -> Observation): Observation
}

data class RuntimeHarnessEvent(val phase: String, val detail: String = "")

data class RuntimeHarnessResult(
    val completed: Boolean,
    val reason: String,
    val events: List<RuntimeHarnessEvent>,
    val evidence: List<String>,
)

/**
 * A small, injectable contract runner. Production AgentRuntime remains the
 * Android integration point; this runner exercises the same local guards,
 * binding ledger, recovery policy, and Stop Gate without Android framework
 * state, making ordering regressions deterministic in unit tests.
 */
class RuntimeContractHarness(
    private val service: RuntimeHarnessAccessibilityService,
    private val planner: RuntimeHarnessPlanner,
    private val clock: RuntimeHarnessClock,
    private val launchablePackages: Set<String>,
    private val packagePolicy: PackagePolicy = PackagePolicy(),
    private val maxSteps: Int = 12,
) {
    suspend fun run(plan: TaskPlan): RuntimeHarnessResult {
        val events = mutableListOf<RuntimeHarnessEvent>()
        val history = mutableListOf<ActionRecord>()
        val ledger = RunLedger(plan)
        val bindings = PredicateBindingStore()
        val recoveryPolicy = RecoveryPolicy()
        val counters = StopGateEvidenceCounters()
        var observation = service.observe().also { events += RuntimeHarnessEvent("fresh_observation", it.observationId) }

        repeat(maxSteps) { step ->
            val milestone = ledger.currentMilestone
            if (milestone == null) {
                return RuntimeHarnessResult(
                    completed = counters.hasLocalEvidence(),
                    reason = if (counters.hasLocalEvidence()) "all milestones proven locally" else "stop gate lacks local evidence",
                    events = events,
                    evidence = ledger.evidenceSummary().lines(),
                )
            }
            val beforeEvidence = MilestoneEvaluator.evaluate(milestone, plan, observation, packagePolicy.primaryPackage, bindings)
            if (beforeEvidence.proven) {
                counters.deterministicEvidenceCount++
                counters.verifiedMilestones++
                bindings.markVerified(milestone.id)
                ledger.advance(beforeEvidence.details.joinToString(" | "))
                events += RuntimeHarnessEvent("evaluate", "before:${milestone.id}")
                events += RuntimeHarnessEvent("stop_gate", "local evidence")
                return@repeat
            }

            val action = planner.nextAction(observation, milestone, history)
            events += RuntimeHarnessEvent("planner", TraceSanitizer.actionType(action))
            if (action is AgentAction.Finish) {
                val done = ledger.complete && counters.hasLocalEvidence()
                events += RuntimeHarnessEvent("stop_gate", if (done) "accepted" else "rejected")
                return RuntimeHarnessResult(done, if (done) action.reason else "stop gate rejected finish", events, ledger.evidenceSummary().lines())
            }

            // The planner may have spent time on a model call. Always refresh
            // the execution observation before any local guard or binding work.
            val executionObservation = service.observe()
            events += RuntimeHarnessEvent("fresh_execution_observation", executionObservation.observationId)

            val preflight = RuntimeStepExecutor.preflight(
                guard = ToolGuard(plan, packagePolicy),
                ledger = ledger,
                proposed = action,
                planningObservation = observation,
                executionObservation = executionObservation,
                milestone = milestone,
                packagePolicy = packagePolicy,
                launchablePackages = launchablePackages,
                goal = plan.goal,
            )
            if (preflight.stale) {
                events += RuntimeHarnessEvent("stale_observation", "action was planned from an older screen")
                val decision = recoveryPolicy.decide(
                    RecoveryContext(currentMilestoneId = milestone.id, failedAction = action, reason = RecoveryReason.SCREEN_UNCHANGED),
                )
                recordRecovery(events, service, decision.action, executionObservation)
                observation = executionObservation
                return@repeat
            }
            val guarded = preflight.guarded
            events += RuntimeHarnessEvent("tool_guard", if (guarded.action != null || guarded.shortCircuit != null) "allowed" else "rejected")
            val guardedAction = preflight.action
            if (guardedAction == null) {
                val decision = recoveryPolicy.decide(RecoveryContext(currentMilestoneId = milestone.id, failedAction = action, reason = RecoveryReason.TARGET_MISSING))
                recordRecovery(events, service, decision.action, executionObservation)
                return@repeat
            }
            events += RuntimeHarnessEvent("safety_guard", if (preflight.safetyFailure == null) "allowed" else "rejected")
            if (preflight.safetyFailure != null) {
                val decision = recoveryPolicy.decide(RecoveryContext(currentMilestoneId = milestone.id, failedAction = guardedAction, reason = RecoveryReason.WRONG_PACKAGE))
                recordRecovery(events, service, decision.action, executionObservation)
                return@repeat
            }
            events += RuntimeHarnessEvent("duplicate", if (preflight.repeatedReason == null) "allowed" else "rejected")
            if (preflight.repeatedReason != null) {
                val decision = recoveryPolicy.decide(RecoveryContext(currentMilestoneId = milestone.id, failedAction = guardedAction, reason = RecoveryReason.REPEATED_ACTION))
                recordRecovery(events, service, decision.action, executionObservation)
                return@repeat
            }
            val prepared = bindings.prepareActionBinding(milestone, guardedAction, executionObservation, runId = "harness")
            events += RuntimeHarnessEvent("prepare_binding", if (prepared.prepared) "prepared" else "rejected")
            if (!prepared.prepared) {
                val decision = recoveryPolicy.decide(RecoveryContext(currentMilestoneId = milestone.id, failedAction = guardedAction, reason = RecoveryReason.AMBIGUOUS_TARGET))
                recordRecovery(events, service, decision.action, executionObservation)
                return@repeat
            }
            if (guarded.shortCircuit != null || guardedAction is AgentAction.BindPredicate) {
                bindings.commitAll(prepared.provisional)
                events += RuntimeHarnessEvent("commit", "observation-only binding")
                counters.successfulObservationActions++
                val evidence = MilestoneEvaluator.evaluate(milestone, plan, executionObservation, packagePolicy.primaryPackage, bindings)
                if (evidence.proven) {
                    counters.deterministicEvidenceCount++
                    counters.verifiedMilestones++
                    bindings.markVerified(milestone.id)
                    ledger.advance(evidence.details.joinToString(" | "))
                }
                events += RuntimeHarnessEvent("evaluate", "observation_only")
                events += RuntimeHarnessEvent("stop_gate", if (evidence.proven) "local evidence" else "waiting")
                observation = executionObservation
                return@repeat
            }
            events += RuntimeHarnessEvent("execute", TraceSanitizer.actionType(guardedAction))
            val execution = service.executeDetailed(guardedAction, executionObservation)
            if (!execution.success) {
                bindings.rollbackAll(prepared.provisional)
                history += ActionRecord(step + 1, guardedAction, false, executionObservation.observationId, executionObservation.observationId, execution.status)
                val decision = recoveryPolicy.decide(RecoveryContext(currentMilestoneId = milestone.id, failedAction = guardedAction, reason = RecoveryReason.INPUT_FAILED))
                recordRecovery(events, service, decision.action, executionObservation)
                return@repeat
            }
            events += RuntimeHarnessEvent("mark_dispatched", "action succeeded")
            bindings.markDispatched(prepared.provisional)
            bindings.commitDispatched(prepared.provisional)
            events += RuntimeHarnessEvent("commit", "committed binding")
            ledger.recordDispatch(guardedAction, executionObservation)
            counters.successfulMutatingActions++
            val after = clock.afterAction(executionObservation, guardedAction, service::observe)
            events += RuntimeHarnessEvent("wait", after.observationId)
            val afterEvidence = MilestoneEvaluator.evaluate(milestone, plan, after, packagePolicy.primaryPackage, bindings)
            history += ActionRecord(step + 1, guardedAction, true, executionObservation.observationId, after.observationId, if (afterEvidence.proven) "evidence: local" else "progress")
            observation = after
            if (afterEvidence.proven) {
                counters.deterministicEvidenceCount++
                counters.verifiedMilestones++
                bindings.markVerified(milestone.id)
                ledger.advance(afterEvidence.details.joinToString(" | "))
                events += RuntimeHarnessEvent("evaluate", "after:${milestone.id}")
                events += RuntimeHarnessEvent("stop_gate", "local evidence")
            } else {
                events += RuntimeHarnessEvent("recover", "none")
                events += RuntimeHarnessEvent("stop_gate", "not yet proven")
            }
        }
        return RuntimeHarnessResult(false, "harness step budget exhausted", events, ledger.evidenceSummary().lines())
    }

    private suspend fun recordRecovery(
        events: MutableList<RuntimeHarnessEvent>,
        service: RuntimeHarnessAccessibilityService,
        action: RecoveryAction,
        observation: Observation,
    ) {
        val result = service.executeRecovery(action, observation)
        events += RuntimeHarnessEvent("recover", "${action.name}:${result.status}:${if (result.success) "success" else "failed"}")
    }
}
