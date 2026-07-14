package com.androidagent.app.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TraceSanitizerTest {
    @Test
    fun inputTextTraceKeepsMetadataOnly() {
        val trace = TraceSanitizer.action(AgentAction.InputText("secret value", nodeId = 4, mode = InputMode.APPEND, submit = true))
        assertTrue(trace.contains("chars=12"))
        assertTrue(trace.contains("APPEND"))
        assertTrue(trace.contains("submit=true"))
        assertFalse(trace.contains("secret value"))
    }

    @Test
    fun goalsAndApiKeysAreNotStoredAsPlainText() {
        val payload = TraceSanitizer.payload(
            mapOf("goal" to "send to 13800138000", "apiKey" to "sk-secret", "plan" to "raw screen"),
        )
        assertFalse(payload.values.any { it.toString().contains("13800138000") })
        assertFalse(payload.values.any { it.toString().contains("sk-secret") })
        assertEquals("[redacted-plan]", payload["plan"])
    }
}
