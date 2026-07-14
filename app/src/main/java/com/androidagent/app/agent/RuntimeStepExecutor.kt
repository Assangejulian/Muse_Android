package com.androidagent.app.agent

/**
 * Shared deterministic preflight used by production Runtime and the contract
 * harness. It deliberately stops before any side effect: fresh observation,
 * stale check, ToolGuard, SafetyGuard, then duplicate-action detection.
 */
data class RuntimeStepPreflight(
    val executionObservation: Observation,
    val stale: Boolean,
    val guarded: GuardResult,
    val action: AgentAction?,
    val safetyFailure: String? = null,
    val repeatedReason: String? = null,
)

object RuntimeStepExecutor {
    fun preflight(
        guard: ToolGuard,
        ledger: RunLedger,
        proposed: AgentAction,
        planningObservation: Observation,
        executionObservation: Observation,
        milestone: TaskMilestone,
        packagePolicy: PackagePolicy,
        launchablePackages: Set<String>,
        goal: GoalContext,
    ): RuntimeStepPreflight {
        if (executionObservation.observationId != planningObservation.observationId) {
            return RuntimeStepPreflight(
                executionObservation = executionObservation,
                stale = true,
                guarded = GuardResult(null, "screen changed before tool dispatch; re-observe before acting"),
                action = null,
            )
        }
        val guarded = guard.normalizeAndValidate(proposed, executionObservation, milestone)
        val action = guarded.action ?: guarded.shortCircuit?.let { proposed }
        if (action == null) {
            return RuntimeStepPreflight(executionObservation, false, guarded, null)
        }
        val safetyFailure = SafetyGuard.validate(
            action,
            executionObservation,
            packagePolicy,
            launchablePackages,
            goal,
        ).exceptionOrNull()?.message
        if (safetyFailure != null) {
            return RuntimeStepPreflight(executionObservation, false, guarded, action, safetyFailure = safetyFailure)
        }
        val repeatedReason = ledger.blockRepeated(action, executionObservation)
        return RuntimeStepPreflight(executionObservation, false, guarded, action, repeatedReason = repeatedReason)
    }
}
