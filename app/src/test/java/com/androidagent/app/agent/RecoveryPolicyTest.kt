package com.androidagent.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryPolicyTest {
    @Test
    fun recoveryUsesStructuredReasonWithoutContentKeywords() {
        val policy = RecoveryPolicy(maxHardRecoveries = 4)
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
    fun missingTargetReobservesThenReplansAndProgressResetsFailures() {
        val policy = RecoveryPolicy(maxHardRecoveries = 6)
        val context = RecoveryContext(currentMilestoneId = "m1", reason = RecoveryReason.TARGET_MISSING)
        assertEquals(RecoveryAction.REOBSERVE, policy.decide(context).action)
        assertEquals(RecoveryAction.REPLAN, policy.decide(context).action)
        policy.resetFailures()
        assertEquals(RecoveryAction.REOBSERVE, policy.decide(context).action)
    }

    @Test
    fun missingPredicateBindingReplansImmediately() {
        val policy = RecoveryPolicy(maxHardRecoveries = 6)
        val context = RecoveryContext(
            currentMilestoneId = "m1",
            failedAction = AgentAction.BindPredicate("m1-p1", selector = ElementSelector(text = "Target")),
            reason = RecoveryReason.TARGET_MISSING,
        )

        assertEquals(RecoveryAction.REPLAN, policy.decide(context).action)
    }

    @Test
    fun networkRecoveryReplansInsteadOfAborting() {
        val policy = RecoveryPolicy(maxActionRetries = 2, maxHardRecoveries = 4)
        val context = RecoveryContext(reason = RecoveryReason.NETWORK_ERROR)
        assertEquals(RecoveryAction.WAIT, policy.decide(context).action)
        assertEquals(RecoveryAction.WAIT, policy.decide(context).action)
        assertEquals(RecoveryAction.REPLAN, policy.decide(context).action)
    }

    @Test
    fun hardBudgetSoftCapsToReplanNeverAbort() {
        val policy = RecoveryPolicy(maxHardRecoveries = 2)
        val context = RecoveryContext(currentMilestoneId = "m1", reason = RecoveryReason.REPEATED_ACTION)
        policy.decide(context)
        policy.decide(context)
        // Soft-cap reached: still REPLAN, never ABORT the agent run.
        val capped = policy.decide(context)
        assertEquals(RecoveryAction.REPLAN, capped.action)
        assertFalse(capped.action == RecoveryAction.ABORT)
        assertTrue(capped.detail.contains("soft-cap") || capped.detail.contains("replan") || capped.detail.contains("Actor"))
    }

    @Test
    fun softRecoveriesDoNotBurnHardBudget() {
        val policy = RecoveryPolicy(maxHardRecoveries = 2)
        val context = RecoveryContext(currentMilestoneId = "m1", reason = RecoveryReason.SCREEN_UNCHANGED)
        repeat(3) {
            assertEquals(RecoveryAction.REOBSERVE, policy.decide(context).action)
        }
        assertEquals(0, policy.consecutiveRecoveries)
        assertFalse(policy.budgetExhausted())
        policy.noteSuccessfulDispatch()
        assertEquals(0, policy.consecutiveSoftRecoveries)
    }

    @Test
    fun unknownResultHandsControlToActorViaReplan() {
        val policy = RecoveryPolicy(maxHardRecoveries = 6)
        val context = RecoveryContext(currentMilestoneId = "m1", reason = RecoveryReason.RESULT_UNKNOWN)
        assertEquals(RecoveryAction.REPLAN, policy.decide(context).action)
    }

    @Test
    fun appNotRespondingRequiresTwoRecoveryObservationsBeforeRelaunch() {
        val policy = RecoveryPolicy(maxHardRecoveries = 6)
        val context = RecoveryContext(
            expectedPackage = "example.app",
            currentMilestoneId = "m1",
            reason = RecoveryReason.APP_NOT_RESPONDING,
        )

        assertEquals(RecoveryAction.REOBSERVE, policy.decide(context).action)
        assertEquals(RecoveryAction.WAIT, policy.decide(context).action)
        assertEquals(RecoveryAction.RELAUNCH, policy.decide(context).action)
    }

    @Test
    fun sensitiveSurfaceBacksThenRelaunchesThenReplans() {
        val policy = RecoveryPolicy(maxHardRecoveries = 6)
        val context = RecoveryContext(
            expectedPackage = "example.app",
            currentMilestoneId = "m1",
            reason = RecoveryReason.SENSITIVE_SURFACE,
        )
        assertEquals(RecoveryAction.BACK, policy.decide(context).action)
        assertEquals(RecoveryAction.RELAUNCH, policy.decide(context).action)
        assertEquals(RecoveryAction.REPLAN, policy.decide(context).action)
    }
}
