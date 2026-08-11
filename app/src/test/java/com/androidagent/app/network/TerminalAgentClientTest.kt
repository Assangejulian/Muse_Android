package com.androidagent.app.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalAgentClientTest {
    @Test
    fun exposesFiftyTurnExecutionBudget() {
        assertEquals(50, TERMINAL_TOOL_TURN_LIMIT)
    }

    @Test
    fun parsesBoundedTerminalRunDecision() {
        val decision = TerminalProtocol.parse(
            """{"action":"run","command":"cmd package list packages","summary":"Inspect apps","timeout_ms":99999}""",
        ) as TerminalDecision.Run

        assertEquals("cmd package list packages", decision.command)
        assertEquals(30_000L, decision.timeoutMillis)
    }

    @Test
    fun parsesNormalFinishDecision() {
        val decision = TerminalProtocol.parse(
            """{"action":"finish","reply":"完成"}""",
        ) as TerminalDecision.Finish

        assertEquals("完成", decision.reply)
    }

    @Test
    fun blocksDestructiveAndSecurityCommands() {
        assertTrue(TerminalCommandPolicy.validate("rm -rf /").isFailure)
        assertTrue(TerminalCommandPolicy.validate("rm -rf /sdcard/Download").isFailure)
        assertTrue(TerminalCommandPolicy.validate("pm clear com.example.app").isFailure)
        assertTrue(TerminalCommandPolicy.validate("settings put secure enabled_accessibility_services x").isFailure)
        assertTrue(TerminalCommandPolicy.validate("cmd package list packages").isSuccess)
    }

    @Test
    fun contextLengthControlsRetainedHistory() {
        val history = listOf(
            "user" to "a".repeat(3_000),
            "assistant" to "b".repeat(3_000),
            "user" to "recent",
        )
        val selected = TerminalContextWindow.select(history, 4_096)

        assertEquals("recent", selected.last().second)
        assertTrue(selected.sumOf { it.second.length } <= 2_048)
    }
}
