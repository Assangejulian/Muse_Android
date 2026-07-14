package com.androidagent.app.agent

import org.junit.Assert.assertEquals
import org.junit.Test

class RecoveryPolicyTest {
    @Test
    fun recoveryUsesStructuredReasonWithoutContentKeywords() {
        val policy = RecoveryPolicy(maxRecoveries = 4)
        assertEquals(RecoveryAction.REPLAN, policy.decide(RecoveryReason.REPEATED_ACTION).action)
        assertEquals(RecoveryAction.REPLAN, policy.decide(RecoveryReason.ABAB_LOOP).action)
    }

    @Test
    fun wrongPackageReobservesThenReplansWithoutRelaunch() {
        val policy = RecoveryPolicy()
        val context = RecoveryContext(
            expectedPackage = "secondary.app",
            currentPackage = "primary.app",
            currentMilestoneId = "external",
            reason = RecoveryReason.WRONG_PACKAGE,
        )
        assertEquals(RecoveryAction.REOBSERVE, policy.decide(context).action)
        assertEquals(RecoveryAction.REPLAN, policy.decide(context).action)
    }

    @Test
    fun missingTargetReobservesOnceThenReplansAndProgressResetsFailures() {
        val policy = RecoveryPolicy(maxRecoveries = 6)
        val context = RecoveryContext(currentMilestoneId = "m1", reason = RecoveryReason.TARGET_MISSING)
        assertEquals(RecoveryAction.REOBSERVE, policy.decide(context).action)
        assertEquals(RecoveryAction.REPLAN, policy.decide(context).action)
        policy.resetFailures()
        assertEquals(RecoveryAction.REOBSERVE, policy.decide(context).action)
    }

    @Test
    fun networkRecoveryUsesIndependentBackoffBudget() {
        val policy = RecoveryPolicy(maxActionRetries = 2, maxRecoveries = 4)
        val context = RecoveryContext(reason = RecoveryReason.NETWORK_ERROR)
        assertEquals(RecoveryAction.WAIT, policy.decide(context).action)
        assertEquals(RecoveryAction.WAIT, policy.decide(context).action)
        assertEquals(RecoveryAction.ABORT, policy.decide(context).action)
    }

    @Test
    fun progressResetsConsecutiveRecoveryBudgetButKeepsDiagnostics() {
        val policy = RecoveryPolicy(maxRecoveries = 2)
        val context = RecoveryContext(currentMilestoneId = "m1", reason = RecoveryReason.SCREEN_UNCHANGED)
        policy.decide(context)
        assertEquals(1, policy.consecutiveRecoveries)
        assertEquals(1, policy.totalRecoveries)
        policy.resetFailures("m1")
        assertEquals(0, policy.consecutiveRecoveries)
        assertEquals(1, policy.totalRecoveries)
        assertEquals(RecoveryAction.REOBSERVE, policy.decide(context).action)
    }

    @Test
    fun unknownResultReobservesWaitsThenReplans() {
        val policy = RecoveryPolicy(maxRecoveries = 6)
        val context = RecoveryContext(currentMilestoneId = "m1", reason = RecoveryReason.RESULT_UNKNOWN)

        assertEquals(RecoveryAction.REOBSERVE, policy.decide(context).action)
        assertEquals(RecoveryAction.WAIT, policy.decide(context).action)
        assertEquals(RecoveryAction.REPLAN, policy.decide(context).action)
    }

    @Test
    fun appNotRespondingRequiresTwoRecoveryObservationsBeforeRelaunch() {
        val policy = RecoveryPolicy(maxRecoveries = 6)
        val context = RecoveryContext(
            expectedPackage = "example.app",
            currentMilestoneId = "m1",
            reason = RecoveryReason.APP_NOT_RESPONDING,
        )

        assertEquals(RecoveryAction.REOBSERVE, policy.decide(context).action)
        assertEquals(RecoveryAction.WAIT, policy.decide(context).action)
        assertEquals(RecoveryAction.RELAUNCH, policy.decide(context).action)
    }
}
