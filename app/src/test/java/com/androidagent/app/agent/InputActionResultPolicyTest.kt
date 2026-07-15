package com.androidagent.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InputActionResultPolicyTest {
    @Test
    fun submitRequiresTextSetAndReadback() {
        val textSetFailed = InputActionResultPolicy.resolve(false, false, true, true)
        assertEquals("text_set_failed", textSetFailed.status)
        assertFalse(textSetFailed.mutationAccepted)

        val verificationFailed = InputActionResultPolicy.resolve(true, false, true, true)
        assertEquals("text_verification_failed", verificationFailed.status)
        assertTrue(verificationFailed.partialMutation)

        val submitFailed = InputActionResultPolicy.resolve(true, true, true, false)
        assertEquals("submit_failed", submitFailed.status)
        assertTrue(submitFailed.partialMutation)

        val success = InputActionResultPolicy.resolve(true, true, true, true)
        assertTrue(success.success)
        assertTrue(success.mutationAccepted)
        assertEquals("text_set_and_submitted", success.status)
    }
}
