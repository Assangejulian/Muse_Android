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
    fun wrongPackageRelaunchesExpectedMilestonePackage() {
        val decision = RecoveryPolicy().decide(
            RecoveryContext(
                expectedPackage = "secondary.app",
                currentPackage = "primary.app",
                currentMilestoneId = "external",
                reason = RecoveryReason.WRONG_PACKAGE,
            ),
        )
        assertEquals(RecoveryAction.RELAUNCH, decision.action)
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
}
