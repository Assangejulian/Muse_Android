package com.androidagent.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionParserTest {
    @Test
    fun parsesClickText() {
        assertEquals(AgentAction.ClickText("Sign in"), ActionParser.parse("""{"action":"click_text","text":"Sign in"}"""))
    }

    @Test
    fun rejectsUnknownAction() {
        assertTrue(runCatching { ActionParser.parse("""{"action":"shell"}""") }.isFailure)
    }

    @Test
    fun clampsWaitDuration() {
        assertEquals(AgentAction.Wait(5000), ActionParser.parse("""{"action":"wait","milliseconds":99999}"""))
    }
}
