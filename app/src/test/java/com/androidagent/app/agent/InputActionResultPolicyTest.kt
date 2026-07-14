package com.androidagent.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InputActionResultPolicyTest {
    @Test
    fun submitRequiresTextSetAndReadback() {
        assertEquals("text_set_failed", InputActionResultPolicy.resolve(false, false, true, true).status)
        assertEquals("text_verification_failed", InputActionResultPolicy.resolve(true, false, true, true).status)
        assertEquals("submit_failed", InputActionResultPolicy.resolve(true, true, true, false).status)
        val success = InputActionResultPolicy.resolve(true, true, true, true)
        assertTrue(success.success)
        assertEquals("text_set_and_submitted", success.status)
    }
}
