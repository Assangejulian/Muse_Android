package com.androidagent.app.network

import com.androidagent.app.agent.ActionParser
import com.androidagent.app.agent.AgentAction
import org.json.JSONArray
import org.json.JSONObject

data class PlannerTurn(
    val callId: String,
    val argumentsJson: String,
    val resultJson: String,
    val reasoningContent: String = "",
    val assistantContent: String? = null,
    val native: Boolean = true,
)

data class PlannedAction(
    val action: AgentAction,
    val callId: String,
    val argumentsJson: String,
    val reasoningContent: String = "",
    val assistantContent: String? = null,
    val native: Boolean,
)

internal class NativeToolsUnsupportedException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

internal class InvalidNativeToolCallException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

internal object NativePlannerProtocol {
    const val TOOL_NAME = "android_action"

    fun toolDefinition(): JSONObject = JSONObject()
        .put("type", "function")
        .put(
            "function",
            JSONObject()
                .put("name", TOOL_NAME)
                .put("description", "Return exactly one Android agent action for the current turn.")
                .put(
                    "parameters",
                    JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "action",
                                    JSONObject()
                                        .put("type", "string")
                                        .put(
                                            "enum",
                                            JSONArray(
                                                listOf(
                                                    "launch_app",
                                                    "click_text",
                                                    "click_node",
                                                    "tap_point",
                                                    "swipe",
                                                    "input_text",
                                                    "submit_input",
                                                    "ensure_toggle",
                                                    "back",
                                                    "home",
                                                    "wait",
                                                    "finish",
                                                    "fail",
                                                ),
                                            ),
                                        ),
                                )
                                .put(
                                    "packageName",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "Required only for launch_app."),
                                )
                                .put(
                                    "text",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "Required for click_text and input_text."),
                                )
                                .put(
                                    "nodeId",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "Required for click_node and ensure_toggle; optional for input_text and submit_input."),
                                )
                                .put(
                                    "x",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("minimum", 0)
                                        .put("maximum", 1000)
                                        .put("description", "Required only for tap_point."),
                                )
                                .put(
                                    "y",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("minimum", 0)
                                        .put("maximum", 1000)
                                        .put("description", "Required only for tap_point."),
                                )
                                .put(
                                    "direction",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("enum", JSONArray(listOf("up", "down", "left", "right")))
                                        .put("description", "Required only for swipe."),
                                )
                                .put(
                                    "milliseconds",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("minimum", 250)
                                        .put("maximum", 5000)
                                        .put("description", "Optional for wait."),
                                )
                                .put(
                                    "desired",
                                    JSONObject()
                                        .put("type", "boolean")
                                        .put("description", "Required only for ensure_toggle."),
                                )
                                .put("selector", selectorSchema())
                                .put("target", selectorSchema())
                                .put(
                                    "mode",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("enum", JSONArray(listOf("REPLACE", "APPEND", "CLEAR")))
                                        .put("description", "Optional input mode."),
                                )
                                .put(
                                    "submit",
                                    JSONObject()
                                        .put("type", "boolean")
                                        .put("description", "Optional input submission request."),
                                )
                                .put(
                                    "reason",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "Required for finish and fail; state direct evidence or a clear blocker."),
                                ),
                        )
                        .put("required", JSONArray().put("action"))
                        .put("additionalProperties", false),
                ),
        )

    fun toolChoice(): JSONObject = JSONObject()
        .put("type", "function")
        .put("function", JSONObject().put("name", TOOL_NAME))

    fun buildMessages(
        systemPrompt: String,
        taskContext: String,
        currentTurnContent: Any,
        toolTurns: List<PlannerTurn>,
    ): JSONArray {
        val messages = JSONArray().put(message("system", systemPrompt))
        if (toolTurns.isEmpty()) {
            messages.put(message("user", mergeUserContent(taskContext, currentTurnContent)))
            return messages
        }

        messages.put(message("user", taskContext))
        toolTurns.forEach { turn ->
            if (turn.native) {
                require(turn.callId.isNotBlank()) { "Native planner turn requires a tool call ID" }
                ActionParser.parse(turn.argumentsJson)
                val assistant = JSONObject()
                    .put("role", "assistant")
                    .put("content", turn.assistantContent ?: JSONObject.NULL)
                    .put(
                        "tool_calls",
                        JSONArray().put(
                            JSONObject()
                                .put("id", turn.callId)
                                .put("type", "function")
                                .put(
                                    "function",
                                    JSONObject()
                                        .put("name", TOOL_NAME)
                                        .put("arguments", turn.argumentsJson),
                                ),
                        ),
                    )
                if (turn.reasoningContent.isNotBlank()) {
                    assistant.put("reasoning_content", turn.reasoningContent)
                }
                messages.put(assistant)
                messages.put(
                    JSONObject()
                        .put("role", "tool")
                        .put("tool_call_id", turn.callId)
                        .put("content", turn.resultJson),
                )
            } else {
                val assistant = message("assistant", turn.argumentsJson)
                if (turn.reasoningContent.isNotBlank()) {
                    assistant.put("reasoning_content", turn.reasoningContent)
                }
                messages.put(assistant)
                messages.put(message("user", "Action result: ${turn.resultJson}"))
            }
        }
        messages.put(message("user", currentTurnContent))
        return messages
    }

    fun parseActionResponse(rawResponse: String): PlannedAction {
        val root = runCatching { JSONObject(rawResponse) }.getOrElse {
            throw InvalidNativeToolCallException("Native tool response was not valid JSON", it)
        }
        val choice = root.optJSONArray("choices")
            ?.optJSONObject(0)
            ?: throw InvalidNativeToolCallException("Native tool response did not contain a choice")
        val finishReason = choice.optString("finish_reason")
        if (finishReason in setOf("length", "content_filter", "insufficient_system_resource")) {
            throw InvalidNativeToolCallException("Native tool response ended with finish_reason=$finishReason")
        }
        val responseMessage = choice
            .optJSONObject("message")
            ?: throw InvalidNativeToolCallException("Native tool response did not contain a message")
        val toolCalls = responseMessage.optJSONArray("tool_calls")
            ?: throw InvalidNativeToolCallException("Model did not return a native tool call")
        if (toolCalls.length() != 1) {
            throw InvalidNativeToolCallException("Model must return exactly one native tool call")
        }
        val toolCall = toolCalls.optJSONObject(0)
            ?: throw InvalidNativeToolCallException("Native tool call was not an object")
        if (toolCall.optString("type") != "function") {
            throw InvalidNativeToolCallException("Native tool call had an unexpected type")
        }
        val callId = toolCall.optString("id")
        if (callId.isBlank()) throw InvalidNativeToolCallException("Native tool call did not contain an ID")
        val function = toolCall.optJSONObject("function")
            ?: throw InvalidNativeToolCallException("Native tool call did not contain a function")
        if (function.optString("name") != TOOL_NAME) {
            throw InvalidNativeToolCallException("Model called an unexpected tool")
        }
        val arguments = function.opt("arguments")
        if (arguments !is String || arguments.isBlank()) {
            throw InvalidNativeToolCallException("Native tool call arguments were not a JSON string")
        }

        val action = try {
            ActionParser.parse(arguments)
        } catch (error: Throwable) {
            throw InvalidNativeToolCallException("Native tool call contained invalid action arguments", error)
        }
        return PlannedAction(
            action = action,
            callId = callId,
            argumentsJson = arguments,
            reasoningContent = responseMessage.optString("reasoning_content"),
            assistantContent = responseMessage.opt("content") as? String,
            native = true,
        )
    }

    private fun mergeUserContent(taskContext: String, currentTurnContent: Any): Any {
        if (currentTurnContent !is JSONArray) return "$taskContext\n$currentTurnContent"
        return JSONArray()
            .put(JSONObject().put("type", "text").put("text", taskContext))
            .apply {
                for (index in 0 until currentTurnContent.length()) {
                    put(currentTurnContent.get(index))
                }
            }
    }

    private fun selectorSchema(): JSONObject = JSONObject()
        .put("type", "object")
        .put("additionalProperties", false)
        .put(
            "properties",
            JSONObject()
                .put("packageName", JSONObject().put("type", "string"))
                .put("viewIdResourceName", JSONObject().put("type", "string"))
                .put("text", JSONObject().put("type", "string"))
                .put("description", JSONObject().put("type", "string"))
                .put("className", JSONObject().put("type", "string"))
                .put("treePath", JSONObject().put("type", "array").put("items", JSONObject().put("type", "integer")))
                .put("bounds", JSONObject().put("type", "string")),
        )

    private fun message(role: String, content: Any): JSONObject =
        JSONObject().put("role", role).put("content", content)
}
