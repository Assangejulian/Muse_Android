package com.androidagent.app.network

import com.androidagent.app.agent.ActionParser
import org.json.JSONObject

/**
 * Assembles an OpenAI-style chat.completion.chunk stream into one PlannedAction.
 * Reasoning deltas are exposed as they arrive so the overlay can update live.
 */
internal class ChatCompletionStreamAssembler {
    private val reasoning = StringBuilder()
    private val content = StringBuilder()
    private val tools = sortedMapOf<Int, MutableToolCall>()
    var finishReason: String = ""
        private set

    fun acceptData(data: String): String {
        val payload = data.trim()
        if (payload.isEmpty() || payload == "[DONE]") return ""
        val root = runCatching { JSONObject(payload) }.getOrNull() ?: return ""
        val choice = root.optJSONArray("choices")?.optJSONObject(0) ?: return ""
        choice.optString("finish_reason").trim().takeIf { it.isNotEmpty() }?.let { finishReason = it }
        val delta = choice.optJSONObject("delta") ?: choice.optJSONObject("message") ?: return ""
        val added = appendText(delta)
        delta.optString("content").takeIf { it.isNotEmpty() }?.let(content::append)
        val toolCalls = delta.optJSONArray("tool_calls")
        if (toolCalls != null) {
            for (index in 0 until toolCalls.length()) {
                val call = toolCalls.optJSONObject(index) ?: continue
                mergeToolCall(call)
            }
        }
        return added
    }

    fun reasoningText(): String = reasoning.toString()

    fun toPlannedAction(): PlannedAction {
        if (finishReason in setOf("length", "content_filter", "insufficient_system_resource")) {
            throw InvalidNativeToolCallException("Native tool response ended with finish_reason=$finishReason")
        }
        val completed = tools.values.firstOrNull { it.name.isNotBlank() && it.arguments.isNotBlank() }
            ?: throw InvalidNativeToolCallException(
                if (reasoning.isNotBlank()) "Model thought without a tool call"
                else "Model did not return a native tool call",
            )
        if (completed.name !in NativePlannerProtocol.knownToolNames()) {
            throw InvalidNativeToolCallException("Model called an unexpected tool")
        }
        val arguments = completed.arguments.toString()
        val action = try {
            ActionParser.parse(arguments, completed.name)
        } catch (error: Throwable) {
            throw InvalidNativeToolCallException("Native tool call contained invalid action arguments", error)
        }
        return PlannedAction(
            action = action,
            callId = completed.id.ifBlank { "stream_0" },
            argumentsJson = arguments,
            reasoningContent = reasoning.toString(),
            assistantContent = content.toString().ifBlank { null },
            native = true,
            thought = NativePlannerProtocol.extractThought(arguments),
            toolName = if (completed.name == NativePlannerProtocol.TOOL_NAME) {
                NativePlannerProtocol.toolNameOf(action)
            } else {
                completed.name
            },
        )
    }

    private fun appendText(delta: JSONObject): String {
        val chunk = NativePlannerProtocol.extractReasoning(delta)
        if (chunk.isEmpty()) return ""
        reasoning.append(chunk)
        return chunk
    }

    private fun mergeToolCall(call: JSONObject) {
        val index = if (call.has("index")) call.optInt("index") else tools.keys.maxOrNull()?.plus(1) ?: 0
        val bucket = tools.getOrPut(index) { MutableToolCall() }
        call.optString("id").trim().takeIf { it.isNotEmpty() }?.let { bucket.id = it }
        val function = call.optJSONObject("function") ?: return
        function.optString("name").trim().takeIf { it.isNotEmpty() }?.let { bucket.name = it }
        when (val raw = function.opt("arguments")) {
            is String -> bucket.arguments.append(raw)
            is JSONObject -> bucket.arguments.append(raw.toString())
        }
    }

    private class MutableToolCall {
        var id: String = ""
        var name: String = ""
        val arguments = StringBuilder()
    }
}
