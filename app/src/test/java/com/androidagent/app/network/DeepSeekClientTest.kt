package com.androidagent.app.network

import com.androidagent.app.agent.AgentAction
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepSeekClientTest {
    @Test
    fun extractsJsonFromMarkdownFence() {
        assertEquals(
            "{\"mode\":\"chat\",\"reply\":\"hello\"}",
            JsonResponse.extractObject("```json\n{\"mode\":\"chat\",\"reply\":\"hello\"}\n```"),
        )
    }

    @Test
    fun rejectsEmptyJsonFence() {
        assertTrue(runCatching { JsonResponse.extractObject("```json\n\n```") }.isFailure)
    }

    @Test
    fun retainsRecentHistoryWithinConservativeContextBudget() {
        val oversized = "a".repeat(ContextWindow.MAX_CONTEXT_TOKENS + 100)
        val selected = ContextWindow.select(listOf("user" to "old", "assistant" to oversized))

        assertEquals(1, selected.size)
        assertEquals(ContextWindow.MAX_CONTEXT_TOKENS / 2, selected.single().second.length)
        assertTrue(selected.single().second.all { it == 'a' })
    }

    @Test
    fun parsesNativeAndroidActionAndPreservesReasoning() {
        val arguments = """{"action":"click_node","nodeId":42}"""
        val response = JSONObject()
            .put(
                "choices",
                JSONArray().put(
                    JSONObject().put(
                        "message",
                        JSONObject()
                            .put("content", "Calling the tool.")
                            .put("reasoning_content", "The visible node is the unique target.")
                            .put(
                                "tool_calls",
                                JSONArray().put(
                                    JSONObject()
                                        .put("id", "call_42")
                                        .put("type", "function")
                                        .put(
                                            "function",
                                            JSONObject()
                                                .put("name", "android_action")
                                                .put("arguments", arguments),
                                        ),
                                ),
                            ),
                    ),
                ),
            )

        val planned = NativePlannerProtocol.parseActionResponse(response.toString())

        assertEquals(AgentAction.ClickNode(42), planned.action)
        assertEquals("call_42", planned.callId)
        assertEquals(arguments, planned.argumentsJson)
        assertEquals("The visible node is the unique target.", planned.reasoningContent)
        assertEquals("Calling the tool.", planned.assistantContent)
        assertTrue(planned.native)
    }

    @Test
    fun rejectsInvalidNativeArgumentsThroughActionParser() {
        val response = """
            {
              "choices": [{
                "message": {
                  "tool_calls": [{
                    "id": "call_bad",
                    "type": "function",
                    "function": {
                      "name": "android_action",
                      "arguments": "{\"action\":\"back\",\"shell\":\"rm -rf /\"}"
                    }
                  }]
                }
              }]
            }
        """.trimIndent()

        assertTrue(runCatching { NativePlannerProtocol.parseActionResponse(response) }.isFailure)
    }

    @Test
    fun rebuildsCompleteAssistantAndToolMessagePair() {
        val arguments = """{"action":"swipe","direction":"up"}"""
        val messages = NativePlannerProtocol.buildMessages(
            systemPrompt = "system",
            taskContext = "goal",
            currentTurnContent = "current screen",
            toolTurns = listOf(
                PlannerTurn(
                    callId = "call_1",
                    argumentsJson = arguments,
                    resultJson = """{"ok":true,"screen":"next"}""",
                    reasoningContent = "Need more results.",
                    assistantContent = "Calling the tool.",
                ),
            ),
        )

        assertEquals(5, messages.length())
        assertEquals("system", messages.getJSONObject(0).getString("role"))
        assertEquals("user", messages.getJSONObject(1).getString("role"))

        val assistant = messages.getJSONObject(2)
        assertEquals("assistant", assistant.getString("role"))
        assertEquals("Calling the tool.", assistant.getString("content"))
        assertEquals("Need more results.", assistant.getString("reasoning_content"))
        val toolCall = assistant.getJSONArray("tool_calls").getJSONObject(0)
        assertEquals("call_1", toolCall.getString("id"))
        assertEquals("android_action", toolCall.getJSONObject("function").getString("name"))
        assertEquals(arguments, toolCall.getJSONObject("function").getString("arguments"))

        val tool = messages.getJSONObject(3)
        assertEquals("tool", tool.getString("role"))
        assertEquals("call_1", tool.getString("tool_call_id"))
        assertEquals("""{"ok":true,"screen":"next"}""", tool.getString("content"))
        assertEquals("current screen", messages.getJSONObject(4).getString("content"))
    }

    @Test
    fun exposesOneClosedAndroidActionSchemaAndForcesItsChoice() {
        val definition = NativePlannerProtocol.toolDefinition()
        val function = definition.getJSONObject("function")
        val parameters = function.getJSONObject("parameters")
        val actionEnum = parameters.getJSONObject("properties").getJSONObject("action").getJSONArray("enum")

        assertEquals("function", definition.getString("type"))
        assertEquals("android_action", function.getString("name"))
        assertFalse(parameters.getBoolean("additionalProperties"))
        assertTrue((0 until actionEnum.length()).any { actionEnum.getString(it) == "ensure_toggle" })
        assertTrue((0 until actionEnum.length()).any { actionEnum.getString(it) == "finish" })
        assertEquals("string", parameters.getJSONObject("properties").getJSONObject("reason").getString("type"))
        assertEquals(
            "android_action",
            NativePlannerProtocol.toolChoice().getJSONObject("function").getString("name"),
        )
    }

    @Test
    fun rejectsParallelNativeToolCalls() {
        val toolCall = { id: String ->
            JSONObject()
                .put("id", id)
                .put("type", "function")
                .put(
                    "function",
                    JSONObject()
                        .put("name", "android_action")
                        .put("arguments", """{"action":"back"}"""),
                )
        }
        val response = JSONObject().put(
            "choices",
            JSONArray().put(
                JSONObject().put(
                    "message",
                    JSONObject().put("tool_calls", JSONArray().put(toolCall("call_1")).put(toolCall("call_2"))),
                ),
            ),
        )

        assertTrue(runCatching { NativePlannerProtocol.parseActionResponse(response.toString()) }.isFailure)
    }

    @Test
    fun rejectsTruncatedNativeToolResponses() {
        val response = JSONObject().put(
            "choices",
            JSONArray().put(
                JSONObject()
                    .put("finish_reason", "length")
                    .put("message", JSONObject().put("tool_calls", JSONArray())),
            ),
        )

        assertTrue(runCatching { NativePlannerProtocol.parseActionResponse(response.toString()) }.isFailure)
    }
}
