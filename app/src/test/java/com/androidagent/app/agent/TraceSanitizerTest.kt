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

    @Test
    fun selectorAndObservationMetadataNeverStoreOriginalText() {
        val action = TraceSanitizer.action(
            AgentAction.InputText(
                text = "private input",
                nodeId = 7,
                target = ElementSelector(text = "private label", description = "private description"),
                submit = true,
            ),
        )
        assertFalse(action.contains("private input"))
        assertFalse(action.contains("private label"))
        assertFalse(action.contains("private description"))

        val before = Observation("example.app", listOf(UiNodeSnapshot(1, "private old", "", "Text", false, false, "0,0,10,10")))
        val after = Observation("example.app", listOf(UiNodeSnapshot(2, "private new", "", "Text", false, false, "0,0,10,10")))
        val delta = TraceSanitizer.observationDelta(before, after)
        assertFalse(delta.contains("private old"))
        assertFalse(delta.contains("private new"))
    }

    @Test
    fun unknownStringPayloadIsMetadataOnly() {
        val payload = TraceSanitizer.payload(mapOf("arbitrary" to "user chat body"))
        assertFalse(payload["arbitrary"].toString().contains("user chat body"))
        assertTrue(payload["arbitrary"].toString().contains("sha256="))
    }
}
