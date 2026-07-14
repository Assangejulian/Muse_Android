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

data class RecoveryDecision(
    val action: RecoveryAction,
    val reason: RecoveryReason,
    val detail: String,
)

class RecoveryPolicy(
    private val maxActionRetries: Int = 2,
    private val maxScreenRepeats: Int = 3,
    private val maxRecoveries: Int = 6,
) {
    private var recoveries = 0
    private val actionRetries = mutableMapOf<String, Int>()

    fun decide(reason: RecoveryReason, actionKey: String? = null): RecoveryDecision {
        if (recoveries >= maxRecoveries) return RecoveryDecision(RecoveryAction.ABORT, reason, "recovery budget exhausted")
        recoveries += 1
        val retryCount = actionKey?.let { actionRetries[it] ?: 0 } ?: 0
        if (actionKey != null) actionRetries[actionKey] = retryCount + 1
        val decision = when (reason) {
            RecoveryReason.SCREEN_UNCHANGED -> if (retryCount < maxScreenRepeats) RecoveryAction.REOBSERVE else RecoveryAction.REPLAN
            RecoveryReason.REPEATED_ACTION, RecoveryReason.ABAB_LOOP -> RecoveryAction.REPLAN
            RecoveryReason.TARGET_MISSING, RecoveryReason.AMBIGUOUS_TARGET -> RecoveryAction.REPLAN
            RecoveryReason.WRONG_PACKAGE -> RecoveryAction.REOBSERVE
            RecoveryReason.INPUT_FAILED -> if (retryCount < maxActionRetries) RecoveryAction.REOBSERVE else RecoveryAction.REPLAN
            RecoveryReason.APP_NOT_RESPONDING -> if (retryCount < 1) RecoveryAction.WAIT else RecoveryAction.RELAUNCH
            RecoveryReason.NETWORK_ERROR -> if (retryCount < maxActionRetries) RecoveryAction.WAIT else RecoveryAction.ABORT
        }
        return RecoveryDecision(decision, reason, "generic recovery decision")
    }
}
