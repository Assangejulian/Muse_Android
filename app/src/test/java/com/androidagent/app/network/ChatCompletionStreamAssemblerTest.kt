package com.androidagent.app.network

import com.androidagent.app.agent.AgentAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatCompletionStreamAssemblerTest {
    @Test
    fun streamsReasoningThenAssemblesOneToolCall() {
        val assembler = ChatCompletionStreamAssembler()
        val first = assembler.acceptData(
            """{"choices":[{"delta":{"reasoning_content":"先打开评论"}}]}""",
        )
        val second = assembler.acceptData(
            """{"choices":[{"delta":{"reasoning_content":"再点赞"}}]}""",
        )
        assembler.acceptData(
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"c1","type":"function","function":{"name":"click_node","arguments":"{\"nodeId\""}}]}}]}""",
        )
        assembler.acceptData(
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":":4}"}}]},"finish_reason":"tool_calls"}]}""",
        )
        assertEquals("先打开评论", first)
        assertEquals("再点赞", second)
        assertEquals("先打开评论再点赞", assembler.reasoningText())
        val planned = assembler.toPlannedAction()
        assertEquals(AgentAction.ClickNode(4), planned.action)
        assertEquals("c1", planned.callId)
        assertTrue(planned.native)
    }
}
