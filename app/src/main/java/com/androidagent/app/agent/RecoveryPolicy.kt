package com.androidagent.app.agent

enum class RecoveryReason {
    SCREEN_UNCHANGED,
    REPEATED_ACTION,
    ABAB_LOOP,
    TARGET_MISSING,
    AMBIGUOUS_TARGET,
    WRONG_PACKAGE,
    INPUT_FAILED,
    APP_NOT_RESPONDING,
    NETWORK_ERROR,
    RESULT_UNKNOWN,
    SENSITIVE_SURFACE,
}

enum class RecoveryAction { REOBSERVE, REPLAN, BACK, DISMISS, WAIT, RELAUNCH, ABORT }

data class RecoveryContext(
    val expectedPackage: String? = null,
    val currentPackage: String? = null,
    val currentMilestoneId: String? = null,
    val currentMilestoneKind: TaskMilestoneKind? = null,
    val failedAction: AgentAction? = null,
    val reason: RecoveryReason,
    val failureCount: Int = 0,
)

data class RecoveryDecision(
    val action: RecoveryAction,
    val reason: RecoveryReason,
    val detail: String,
    val failureCount: Int = 0,
)

/**
 * Model-first recovery policy.
 *
 * Design rules (learned from multi-step GUI runs on any dynamic app):
 * 1. Soft uncertainty (unknown settle, missing target, unchanged screen) must
 *    never abort the run — hand control back to the Actor via REPLAN / REOBSERVE.
 * 2. Soft recoveries never burn the hard budget.
 * 3. Hard-budget exhaustion returns REPLAN, not ABORT. The outer loop's step
 *    budget and wall-clock timeout are the real stop conditions.
 * 4. ABORT is reserved for truly terminal local faults (no package to relaunch
 *    after repeated ANR). Network exhaustion also REPLANs so the Actor can wait.
 */
class RecoveryPolicy(
    private val maxActionRetries: Int = 3,
    private val maxScreenRepeats: Int = 3,
    private val maxHardRecoveries: Int = 48,
) {
    private val consecutiveFailuresByKey = mutableMapOf<String, Int>()
    var consecutiveRecoveries: Int = 0
        private set
    /** @deprecated Soft counters are no longer used to abort runs; kept for diagnostics. */
    var consecutiveSoftRecoveries: Int = 0
        private set
    var totalRecoveries: Int = 0
        private set

    fun failureCounts(): Map<String, Int> = consecutiveFailuresByKey.toMap()

    /**
     * True when hard recoveries are saturated. Callers must treat this as
     * "prefer REPLAN / continue", never as a hard process kill by itself.
     */
    fun budgetExhausted(): Boolean = consecutiveRecoveries >= maxHardRecoveries

    fun decide(context: RecoveryContext): RecoveryDecision {
        val key = key(context)
        val count = maxOf(context.failureCount, consecutiveFailuresByKey[key] ?: 0)
        consecutiveFailuresByKey[key] = count + 1
        totalRecoveries += 1

        val action = if (consecutiveRecoveries >= maxHardRecoveries) {
            // Soft-cap: never ABORT the whole agent run for recovery volume.
            RecoveryAction.REPLAN
        } else {
            when (context.reason) {
                RecoveryReason.SCREEN_UNCHANGED ->
                    if (count < maxScreenRepeats) RecoveryAction.REOBSERVE else RecoveryAction.REPLAN
                RecoveryReason.REPEATED_ACTION, RecoveryReason.ABAB_LOOP -> RecoveryAction.REPLAN
                RecoveryReason.TARGET_MISSING, RecoveryReason.AMBIGUOUS_TARGET -> when {
                    context.failedAction is AgentAction.BindPredicate -> RecoveryAction.REPLAN
                    count == 0 -> RecoveryAction.REOBSERVE
                    else -> RecoveryAction.REPLAN
                }
                RecoveryReason.WRONG_PACKAGE ->
                    if (count == 0) RecoveryAction.REOBSERVE else RecoveryAction.REPLAN
                RecoveryReason.INPUT_FAILED ->
                    if (count < maxActionRetries) RecoveryAction.REOBSERVE else RecoveryAction.REPLAN
                RecoveryReason.APP_NOT_RESPONDING -> when {
                    count == 0 -> RecoveryAction.REOBSERVE
                    count == 1 -> RecoveryAction.WAIT
                    !context.expectedPackage.isNullOrBlank() -> RecoveryAction.RELAUNCH
                    else -> RecoveryAction.REPLAN
                }
                RecoveryReason.NETWORK_ERROR ->
                    if (count < maxActionRetries) RecoveryAction.WAIT else RecoveryAction.REPLAN
                RecoveryReason.RESULT_UNKNOWN -> RecoveryAction.REPLAN
                RecoveryReason.SENSITIVE_SURFACE -> when {
                    count == 0 -> RecoveryAction.BACK
                    count == 1 -> RecoveryAction.RELAUNCH
                    else -> RecoveryAction.REPLAN
                }
            }
        }

        if (isSoft(action)) {
            consecutiveSoftRecoveries += 1
        } else if (action != RecoveryAction.ABORT) {
            consecutiveRecoveries += 1
        }

        val detail = if (consecutiveRecoveries >= maxHardRecoveries && action == RecoveryAction.REPLAN) {
            "hard recovery soft-cap reached; handing control back to Actor"
        } else {
            "${context.reason.name.lowercase()} recovery"
        }
        return RecoveryDecision(action, context.reason, detail, count + 1)
    }

    fun decide(reason: RecoveryReason, actionKey: String? = null): RecoveryDecision =
        decide(RecoveryContext(currentMilestoneId = actionKey, reason = reason))

    fun resetFailures(milestoneId: String? = null) {
        if (milestoneId.isNullOrBlank()) {
            consecutiveFailuresByKey.clear()
        } else {
            consecutiveFailuresByKey.keys.removeIf { it.split('|').getOrNull(1) == milestoneId }
        }
        consecutiveRecoveries = 0
        consecutiveSoftRecoveries = 0
    }

    /** Successful tool dispatch: clear soft noise and decay hard debt. */
    fun noteSuccessfulDispatch() {
        consecutiveSoftRecoveries = 0
        if (consecutiveRecoveries > 0) {
            consecutiveRecoveries = (consecutiveRecoveries - 2).coerceAtLeast(0)
        }
    }

    fun networkBackoffMillis(failureCount: Int): Long =
        (400L * (1L shl failureCount.coerceIn(0, 4))).coerceAtMost(8_000L)

    private fun isSoft(action: RecoveryAction): Boolean =
        action == RecoveryAction.REOBSERVE || action == RecoveryAction.WAIT

    private fun key(context: RecoveryContext): String =
        listOf(
            context.reason.name,
            context.currentMilestoneId.orEmpty(),
            context.failedAction?.let { it::class.simpleName }.orEmpty(),
        ).joinToString("|")
}
