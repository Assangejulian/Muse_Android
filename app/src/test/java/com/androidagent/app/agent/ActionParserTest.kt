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
    fun parsesFinalClick() {
        assertEquals(AgentAction.ClickNode(7), ActionParser.parse("""{"action":"click_node","nodeId":7}"""))
    }

    @Test
    fun parsesNormalizedVisualPoint() {
        assertEquals(AgentAction.TapPoint(875, 520), ActionParser.parse("""{"action":"tap_point","x":875,"y":520}"""))
    }

    @Test
    fun rejectsUnknownAction() {
        assertTrue(runCatching { ActionParser.parse("""{"action":"shell"}""") }.isFailure)
    }

    @Test
    fun clampsWaitDuration() {
        assertEquals(AgentAction.Wait(5000), ActionParser.parse("""{"action":"wait","milliseconds":99999}"""))
    }

    @Test
    fun parsesJsonSurroundedByModelText() {
        assertEquals(
            AgentAction.Finish("done"),
            ActionParser.parse("Result:\n{\"action\":\"finish\",\"reason\":\"done\"}\nEnd"),
        )
    }

    @Test
    fun ignoresMarkdownFenceMentionAfterJson() {
        assertEquals(
            AgentAction.Finish("done"),
            ActionParser.parse("{\"action\":\"finish\",\"reason\":\"done\"}\n```json\n```"),
        )
    }
}
