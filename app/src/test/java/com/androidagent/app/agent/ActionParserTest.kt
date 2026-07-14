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
    fun rejectsOutOfRangeWaitDuration() {
        assertTrue(runCatching { ActionParser.parse("""{"action":"wait","milliseconds":99999}""") }.isFailure)
    }

    @Test
    fun rejectsFieldsThatDoNotBelongToTheAction() {
        assertTrue(runCatching { ActionParser.parse("""{"action":"back","text":"hidden"}""") }.isFailure)
        assertTrue(runCatching { ActionParser.parse("""{"action":"ensure_toggle","nodeId":1}""") }.isFailure)
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

    @Test
    fun parsesInputModeTargetAndSubmitWhileKeepingLegacyShape() {
        val action = ActionParser.parse(
            """{"action":"input_text","text":"beta","nodeId":2,"mode":"APPEND","submit":true,"target":{"packageName":"example.app","className":"EditText"}}""",
        )
        assertEquals(
            AgentAction.InputText(
                text = "beta",
                nodeId = 2,
                target = ElementSelector(packageName = "example.app", className = "EditText"),
                mode = InputMode.APPEND,
                submit = true,
            ),
            action,
        )
    }
}
