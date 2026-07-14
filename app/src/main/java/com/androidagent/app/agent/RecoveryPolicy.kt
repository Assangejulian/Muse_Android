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
    private var recoveries = 0
    private val failures = mutableMapOf<String, Int>()

    fun decide(context: RecoveryContext): RecoveryDecision {
        val key = key(context)
        val count = maxOf(context.failureCount, failures[key] ?: 0)
        if (recoveries >= maxRecoveries) return RecoveryDecision(RecoveryAction.ABORT, context.reason, "recovery budget exhausted", count)
        recoveries += 1
        failures[key] = count + 1
        val decision = when (context.reason) {
            RecoveryReason.SCREEN_UNCHANGED -> if (count < maxScreenRepeats) RecoveryAction.REOBSERVE else RecoveryAction.REPLAN
            RecoveryReason.REPEATED_ACTION, RecoveryReason.ABAB_LOOP -> RecoveryAction.REPLAN
            RecoveryReason.TARGET_MISSING, RecoveryReason.AMBIGUOUS_TARGET -> if (count == 0) RecoveryAction.REOBSERVE else RecoveryAction.REPLAN
            RecoveryReason.WRONG_PACKAGE -> when {
                !context.expectedPackage.isNullOrBlank() -> RecoveryAction.RELAUNCH
                count == 0 -> RecoveryAction.REOBSERVE
                else -> RecoveryAction.REPLAN
            }
            RecoveryReason.INPUT_FAILED -> if (count < maxActionRetries) RecoveryAction.REOBSERVE else RecoveryAction.REPLAN
            RecoveryReason.APP_NOT_RESPONDING -> when {
                !context.expectedPackage.isNullOrBlank() -> RecoveryAction.RELAUNCH
                count == 0 -> RecoveryAction.WAIT
                else -> RecoveryAction.ABORT
            }
            RecoveryReason.NETWORK_ERROR -> if (count < maxActionRetries) RecoveryAction.WAIT else RecoveryAction.ABORT
        }
        return RecoveryDecision(decision, context.reason, "${context.reason.name.lowercase()} recovery", count + 1)
    }

    /** Compatibility overload for callers that only have a reason and action key. */
    fun decide(reason: RecoveryReason, actionKey: String? = null): RecoveryDecision =
        decide(RecoveryContext(currentMilestoneId = actionKey, reason = reason))

    fun resetFailures() {
        failures.clear()
    }

    fun networkBackoffMillis(failureCount: Int): Long =
        (400L * (1L shl failureCount.coerceIn(0, 4))).coerceAtMost(8_000L)

    private fun key(context: RecoveryContext): String =
        listOf(context.reason.name, context.currentMilestoneId.orEmpty(), context.failedAction?.let { it::class.simpleName }.orEmpty()).joinToString("|")
}
