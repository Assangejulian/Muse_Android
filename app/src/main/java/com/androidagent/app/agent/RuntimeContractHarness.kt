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
    suspend fun afterAction(
        before: Observation,
        action: AgentAction,
        observe: suspend () -> Observation,
    ): RuntimeStepSettleResult
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
        val sideEffects = RunScopedSideEffectLedger("harness")
        val preDispatchSnapshots = PreDispatchEvidenceStore()
        val recoveryPolicy = RecoveryPolicy()
        val counters = StopGateEvidenceCounters()
        val engine = RuntimeStepEngine(object : RuntimeStepDriver {
            override suspend fun executeDetailed(action: AgentAction, observation: Observation): ActionExecutionResult =
                service.executeDetailed(action, observation)

            override suspend fun settle(before: Observation, action: AgentAction): RuntimeStepSettleResult =
                clock.afterAction(before, action, service::observe)

            override suspend fun executeRecovery(
                decision: RecoveryDecision,
                observation: Observation,
            ): RuntimeStepRecoveryResult {
                val result = service.executeRecovery(decision.action, observation)
                return RuntimeStepRecoveryResult(result.success, service.observe(), result.detail.ifBlank { result.status })
            }
        })
        var observation = service.observe().also { events += RuntimeHarnessEvent("fresh_observation", it.observationId) }

        repeat(maxSteps) { step ->
            val milestone = ledger.currentMilestone
            if (milestone == null) {
                return RuntimeHarnessResult(
                    completed = counters.hasLocalEvidence(),
                    reason = if (counters.hasLocalEvidence()) "all milestones proven locally" else "stop gate lacks local evidence",
                    events = events,
                    evidence = ledger.evidenceSummary().lines(),
                ).also { sideEffects.clear(); preDispatchSnapshots.clear() }
            }
            val beforeEvidence = MilestoneEvaluator.evaluate(
                milestone,
                plan,
                observation,
                packagePolicy.primaryPackage,
                bindings,
                preDispatchSnapshots = preDispatchSnapshots,
            )
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
                return RuntimeHarnessResult(done, if (done) action.reason else "stop gate rejected finish", events, ledger.evidenceSummary().lines()).also {
                    sideEffects.clear(); preDispatchSnapshots.clear()
                }
            }

            // The planner may have spent time on a model call. Always refresh
            // the execution observation before any local guard or binding work.
            val executionObservation = service.observe()
            events += RuntimeHarnessEvent("fresh_execution_observation", executionObservation.observationId)

            val result = engine.execute(
                RuntimeStepRequest(
                    step = step + 1,
                    proposed = action,
                    planningObservation = observation,
                    executionObservation = executionObservation,
                    plan = plan,
                    milestone = milestone,
                    guard = ToolGuard(plan, packagePolicy),
                    ledger = ledger,
                    bindings = bindings,
                    recoveryPolicy = recoveryPolicy,
                    packagePolicy = packagePolicy,
                    launchablePackages = launchablePackages,
                    goal = plan.goal,
                    targetPackage = packagePolicy.primaryPackage,
                    evidenceCounters = counters,
                    runId = "harness",
                    sideEffects = sideEffects,
                    preDispatchSnapshots = preDispatchSnapshots,
                ),
            )
            events += result.events.map { RuntimeHarnessEvent(it.phase, it.detail) }
            observation = result.after
            result.action?.let { executedAction ->
                history += ActionRecord(
                    step = step + 1,
                    action = executedAction,
                    success = result.execution?.success == true,
                    beforeFingerprint = result.before.observationId,
                    afterFingerprint = result.after.observationId,
                    result = result.reason,
                )
            }
            if (result.status == RuntimeStepStatus.ABORTED) {
                return RuntimeHarnessResult(false, result.reason, events, ledger.evidenceSummary().lines()).also {
                    sideEffects.clear(); preDispatchSnapshots.clear()
                }
            }
            if (result.needsReplan) {
                return RuntimeHarnessResult(false, result.reason, events, ledger.evidenceSummary().lines()).also {
                    sideEffects.clear(); preDispatchSnapshots.clear()
                }
            }
        }
        return RuntimeHarnessResult(false, "harness step budget exhausted", events, ledger.evidenceSummary().lines()).also {
            sideEffects.clear(); preDispatchSnapshots.clear()
        }
    }
}
