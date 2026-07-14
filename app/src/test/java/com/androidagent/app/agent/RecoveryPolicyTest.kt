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
}
