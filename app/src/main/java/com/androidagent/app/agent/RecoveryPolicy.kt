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

class RecoveryPolicy(
    private val maxActionRetries: Int = 2,
    private val maxScreenRepeats: Int = 3,
    private val maxRecoveries: Int = 6,
) {
    private val consecutiveFailuresByKey = mutableMapOf<String, Int>()
    var consecutiveRecoveries: Int = 0
        private set
    var totalRecoveries: Int = 0
        private set

    fun failureCounts(): Map<String, Int> = consecutiveFailuresByKey.toMap()

    fun decide(context: RecoveryContext): RecoveryDecision {
        val key = key(context)
        val count = maxOf(context.failureCount, consecutiveFailuresByKey[key] ?: 0)
        if (consecutiveRecoveries >= maxRecoveries) {
            return RecoveryDecision(RecoveryAction.ABORT, context.reason, "consecutive recovery budget exhausted", count)
        }
        consecutiveFailuresByKey[key] = count + 1
        consecutiveRecoveries += 1
        totalRecoveries += 1
        val decision = when (context.reason) {
            RecoveryReason.SCREEN_UNCHANGED -> if (count < maxScreenRepeats) RecoveryAction.REOBSERVE else RecoveryAction.REPLAN
            RecoveryReason.REPEATED_ACTION, RecoveryReason.ABAB_LOOP -> RecoveryAction.REPLAN
            RecoveryReason.TARGET_MISSING, RecoveryReason.AMBIGUOUS_TARGET -> if (count == 0) RecoveryAction.REOBSERVE else RecoveryAction.REPLAN
            RecoveryReason.WRONG_PACKAGE -> if (count == 0) RecoveryAction.REOBSERVE else RecoveryAction.REPLAN
            RecoveryReason.INPUT_FAILED -> if (count < maxActionRetries) RecoveryAction.REOBSERVE else RecoveryAction.REPLAN
            RecoveryReason.APP_NOT_RESPONDING -> when {
                count == 0 -> RecoveryAction.REOBSERVE
                count == 1 -> RecoveryAction.WAIT
                !context.expectedPackage.isNullOrBlank() -> RecoveryAction.RELAUNCH
                else -> RecoveryAction.ABORT
            }
            RecoveryReason.NETWORK_ERROR -> if (count < maxActionRetries) RecoveryAction.WAIT else RecoveryAction.ABORT
            RecoveryReason.RESULT_UNKNOWN -> when (count) {
                0 -> RecoveryAction.REOBSERVE
                1 -> RecoveryAction.WAIT
                else -> RecoveryAction.REPLAN
            }
        }
        return RecoveryDecision(decision, context.reason, "${context.reason.name.lowercase()} recovery", count + 1)
    }

    /** Compatibility overload for callers that only have a reason and action key. */
    fun decide(reason: RecoveryReason, actionKey: String? = null): RecoveryDecision =
        decide(RecoveryContext(currentMilestoneId = actionKey, reason = reason))

    /** Call when the current milestone made progress or was proven. */
    fun resetFailures(milestoneId: String? = null) {
        if (milestoneId.isNullOrBlank()) {
            consecutiveFailuresByKey.clear()
        } else {
            consecutiveFailuresByKey.keys.removeIf { it.split('|').getOrNull(1) == milestoneId }
        }
        consecutiveRecoveries = 0
    }

    fun networkBackoffMillis(failureCount: Int): Long =
        (400L * (1L shl failureCount.coerceIn(0, 4))).coerceAtMost(8_000L)

    private fun key(context: RecoveryContext): String =
        listOf(context.reason.name, context.currentMilestoneId.orEmpty(), context.failedAction?.let { it::class.simpleName }.orEmpty()).joinToString("|")
}
