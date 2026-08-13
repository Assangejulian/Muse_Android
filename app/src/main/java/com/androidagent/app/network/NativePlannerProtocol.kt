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
    val toolName: String = "android_action",
)

data class PlannedAction(
    val action: AgentAction,
    val callId: String,
    val argumentsJson: String,
    val reasoningContent: String = "",
    val assistantContent: String? = null,
    val native: Boolean,
    val thought: String = "",
    val toolName: String = NativePlannerProtocol.toolNameOf(action),
)

internal class NativeToolsUnsupportedException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

internal class InvalidNativeToolCallException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

internal object NativePlannerProtocol {
    const val TOOL_NAME = "android_action"

    fun toolNameOf(action: AgentAction): String = when (action) {
        is AgentAction.LaunchApp -> "launch_app"
        is AgentAction.ClickText -> "click_text"
        is AgentAction.ClickNode -> "click_node"
        is AgentAction.TapPoint -> "tap_point"
        is AgentAction.Swipe -> "swipe"
        is AgentAction.ScrollUntil -> "scroll_until"
        is AgentAction.InputText -> "input_text"
        is AgentAction.SubmitInput -> "submit_input"
        is AgentAction.EnsureToggle -> "ensure_toggle"
        is AgentAction.BindPredicate -> "bind_predicate"
        is AgentAction.FindNodes -> "find_nodes"
        is AgentAction.ReadNode -> "read_node"
        is AgentAction.WaitUntil -> "wait_until"
        is AgentAction.Terminal -> "terminal"
        is AgentAction.Wait -> "wait"
        AgentAction.Back -> "back"
        AgentAction.Home -> "home"
        is AgentAction.Finish -> "finish"
        is AgentAction.Fail -> "fail"
    }

    fun knownToolNames(terminalAvailable: Boolean = true): Set<String> = buildSet {
        add(TOOL_NAME)
        addAll(
            listOf(
                "find_nodes", "read_node", "click_node", "click_text", "swipe", "scroll_until",
                "input_text", "submit_input", "launch_app", "ensure_toggle", "tap_point",
                "wait", "wait_until", "back", "home", "bind_predicate", "finish", "fail",
            ),
        )
        if (terminalAvailable) add("terminal")
    }

    fun toolDefinitions(terminalAvailable: Boolean = false): JSONArray {
        val tools = JSONArray()
        fun add(tool: JSONObject) {
            tools.put(tool)
        }
        add(
            tool(
                "find_nodes",
                "Search the full accessibility tree (not just the truncated Screen list) by text, description, viewId, className, or state flags. Read-only.",
                queryProperties().put("thought", thoughtSchema()),
            ),
        )
        add(
            tool(
                "read_node",
                "Read one node's current text, description, checked, and selected state. Use after a same-page action to verify state.",
                JSONObject()
                    .put("nodeId", intSchema("Node id from Screen or find_nodes."))
                    .put("selector", selectorSchema())
                    .put("thought", thoughtSchema()),
            ),
        )
        add(
            tool(
                "click_node",
                "Click the named node. If it is not itself clickable, Muse taps its bounds instead of a larger parent row.",
                JSONObject()
                    .put("nodeId", intSchema("Required node id."))
                    .put("selector", selectorSchema())
                    .put("predicateId", stringSchema("Optional predicate id."))
                    .put("thought", thoughtSchema()),
                required = listOf("nodeId"),
            ),
        )
        add(
            tool(
                "click_text",
                "Click the unique node whose text or description equals this value.",
                JSONObject()
                    .put("text", stringSchema("Exact visible text or description."))
                    .put("predicateId", stringSchema("Optional predicate id."))
                    .put("thought", thoughtSchema()),
                required = listOf("text"),
            ),
        )
        add(
            tool(
                "swipe",
                "One directional swipe. up reveals content below.",
                JSONObject()
                    .put("direction", directionSchema())
                    .put("thought", thoughtSchema()),
                required = listOf("direction"),
            ),
        )
        add(
            tool(
                "scroll_until",
                "Swipe locally until a query matches or maxSwipes is reached. One tool call, many swipes.",
                queryProperties()
                    .put("direction", directionSchema())
                    .put("maxSwipes", intSchema("1-12, default 6.", minimum = 1, maximum = 12))
                    .put("thought", thoughtSchema()),
                required = listOf("direction"),
            ),
        )
        add(
            tool(
                "input_text",
                "Type into an editable field. Prefer user-provided or on-screen values, never the whole goal sentence.",
                JSONObject()
                    .put("text", stringSchema("Exact text to enter."))
                    .put("nodeId", intSchema("Optional editable node id."))
                    .put("target", selectorSchema())
                    .put("mode", JSONObject().put("type", "string").put("enum", JSONArray(listOf("REPLACE", "APPEND", "CLEAR"))))
                    .put("submit", JSONObject().put("type", "boolean"))
                    .put("predicateId", stringSchema("Optional predicate id."))
                    .put("thought", thoughtSchema()),
                required = listOf("text"),
            ),
        )
        add(
            tool(
                "submit_input",
                "Submit the focused or specified field after exact text readback.",
                JSONObject()
                    .put("nodeId", intSchema("Optional editable node id."))
                    .put("target", selectorSchema())
                    .put("predicateId", stringSchema("Optional predicate id."))
                    .put("thought", thoughtSchema()),
            ),
        )
        add(
            tool(
                "launch_app",
                "Launch one installed package from INSTALLED APPS.",
                JSONObject()
                    .put("packageName", stringSchema("Exact installed package name."))
                    .put("thought", thoughtSchema()),
                required = listOf("packageName"),
            ),
        )
        add(
            tool(
                "ensure_toggle",
                "Set a boolean control that exposes checked state.",
                JSONObject()
                    .put("nodeId", intSchema("Required node id."))
                    .put("desired", JSONObject().put("type", "boolean"))
                    .put("selector", selectorSchema())
                    .put("predicateId", stringSchema("Optional predicate id."))
                    .put("thought", thoughtSchema()),
                required = listOf("nodeId", "desired"),
            ),
        )
        add(
            tool(
                "tap_point",
                "Normalized screenshot tap 0-1000. Only when a screenshot is supplied and no usable node exists.",
                JSONObject()
                    .put("x", intSchema("Normalized X 0-1000.", minimum = 0, maximum = 1000))
                    .put("y", intSchema("Normalized Y 0-1000.", minimum = 0, maximum = 1000))
                    .put("thought", thoughtSchema()),
                required = listOf("x", "y"),
            ),
        )
        add(
            tool(
                "wait",
                "Bounded delay, then re-observe.",
                JSONObject()
                    .put("milliseconds", intSchema("250-5000, default 1000.", minimum = 250, maximum = 5000))
                    .put("thought", thoughtSchema()),
            ),
        )
        add(
            tool(
                "wait_until",
                "Poll the live tree until a query matches or the timeout elapses.",
                queryProperties()
                    .put("milliseconds", intSchema("250-8000, default 3000.", minimum = 250, maximum = 8000))
                    .put("thought", thoughtSchema()),
            ),
        )
        add(tool("back", "System Back.", JSONObject().put("thought", thoughtSchema())))
        add(tool("home", "System Home.", JSONObject().put("thought", thoughtSchema())))
        add(
            tool(
                "bind_predicate",
                "Optional observation-only predicate bind. Ordinary actions do not need this.",
                JSONObject()
                    .put("predicateId", stringSchema("Required predicate id."))
                    .put("nodeId", intSchema("Optional node id."))
                    .put("selector", selectorSchema())
                    .put("thought", thoughtSchema()),
                required = listOf("predicateId"),
            ),
        )
        if (terminalAvailable) {
            add(
                tool(
                    "terminal",
                    "One bounded Android shell command via Shizuku.",
                    JSONObject()
                        .put("command", stringSchema("One shell command.", maxLength = 16000))
                        .put("timeoutMillis", intSchema("250-30000, default 5000.", minimum = 250, maximum = 30000))
                        .put("thought", thoughtSchema()),
                    required = listOf("command"),
                ),
            )
        }
        add(
            tool(
                "finish",
                "Stop because the current state plus confirmed tool history supports the whole user goal.",
                JSONObject()
                    .put("reason", stringSchema("Direct observable completion evidence."))
                    .put("thought", thoughtSchema()),
                required = listOf("reason"),
            ),
        )
        add(
            tool(
                "fail",
                "Stop because of a real blocker that cannot be resolved.",
                JSONObject()
                    .put("reason", stringSchema("Clear non-transient blocker."))
                    .put("thought", thoughtSchema()),
                required = listOf("reason"),
            ),
        )
        return tools
    }

    fun toolChoice(): Any = "required"

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
                ActionParser.parse(turn.argumentsJson, turn.toolName)
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
                                        .put("name", turn.toolName.ifBlank { toolNameOf(ActionParser.parse(turn.argumentsJson)) })
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
        val reasoning = extractReasoning(responseMessage, choice)
        val toolCalls = responseMessage.optJSONArray("tool_calls")
        if (toolCalls == null || toolCalls.length() == 0) {
            throw InvalidNativeToolCallException(
                if (reasoning.isNotBlank()) {
                    "Model thought without a tool call"
                } else {
                    "Model did not return a native tool call"
                },
            )
        }
        val toolCall = (0 until toolCalls.length())
            .mapNotNull { toolCalls.optJSONObject(it) }
            .firstOrNull { call ->
                call.optString("type").ifBlank { "function" } == "function" &&
                    call.optJSONObject("function")?.optString("name").orEmpty() in knownToolNames()
            }
            ?: throw InvalidNativeToolCallException("Native tool call was not an object")
        val callType = toolCall.optString("type").ifBlank { "function" }
        if (callType != "function") {
            throw InvalidNativeToolCallException("Native tool call had an unexpected type")
        }
        val callId = toolCall.optString("id")
        if (callId.isBlank()) throw InvalidNativeToolCallException("Native tool call did not contain an ID")
        val function = toolCall.optJSONObject("function")
            ?: throw InvalidNativeToolCallException("Native tool call did not contain a function")
        val toolName = function.optString("name")
        if (toolName !in knownToolNames()) {
            throw InvalidNativeToolCallException("Model called an unexpected tool")
        }
        val arguments = when (val raw = function.opt("arguments")) {
            is String -> raw
            is JSONObject -> raw.toString()
            else -> ""
        }
        if (arguments.isBlank()) {
            throw InvalidNativeToolCallException("Native tool call arguments were not a JSON string")
        }

        val action = try {
            ActionParser.parse(arguments, toolName)
        } catch (error: Throwable) {
            throw InvalidNativeToolCallException("Native tool call contained invalid action arguments", error)
        }
        return PlannedAction(
            action = action,
            callId = callId,
            argumentsJson = arguments,
            reasoningContent = reasoning,
            assistantContent = responseMessage.opt("content") as? String,
            native = true,
            thought = extractThought(arguments),
            toolName = if (toolName == TOOL_NAME) toolNameOf(action) else toolName,
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

    private fun tool(
        name: String,
        description: String,
        properties: JSONObject,
        required: List<String> = emptyList(),
    ): JSONObject {
        val parameters = JSONObject()
            .put("type", "object")
            .put("properties", properties)
            .put("additionalProperties", false)
        if (required.isNotEmpty()) parameters.put("required", JSONArray(required))
        return JSONObject()
            .put("type", "function")
            .put(
                "function",
                JSONObject()
                    .put("name", name)
                    .put("description", description)
                    .put("parameters", parameters),
            )
    }

    private fun queryProperties(): JSONObject {
        val properties = JSONObject()
            .put("text", stringSchema("Substring match on node text."))
            .put("description", stringSchema("Substring match on content description."))
            .put("viewId", stringSchema("Substring match on view id."))
            .put("className", stringSchema("Substring match on class name."))
            .put("clickable", JSONObject().put("type", "boolean"))
            .put("checked", JSONObject().put("type", "boolean"))
            .put("selected", JSONObject().put("type", "boolean"))
            .put("scrollable", JSONObject().put("type", "boolean"))
            .put("enabled", JSONObject().put("type", "boolean"))
            .put("visible", JSONObject().put("type", "boolean"))
            .put("limit", intSchema("Max matches, 1-16, default 8.", minimum = 1, maximum = 16))
            .put("query", JSONObject().put("type", "object").put("additionalProperties", true).put("description", "Optional nested query object."))
        return properties
    }

    private fun thoughtSchema(): JSONObject =
        stringSchema("Optional Chinese thinking process. Shown to the user as the chain of thought, not as a tool log.")

    private fun stringSchema(description: String, maxLength: Int? = null): JSONObject =
        JSONObject().put("type", "string").put("description", description).also { schema ->
            if (maxLength != null) schema.put("maxLength", maxLength)
        }

    private fun intSchema(description: String, minimum: Int? = null, maximum: Int? = null): JSONObject =
        JSONObject().put("type", "integer").put("description", description).also { schema ->
            if (minimum != null) schema.put("minimum", minimum)
            if (maximum != null) schema.put("maximum", maximum)
        }

    private fun directionSchema(): JSONObject = JSONObject()
        .put("type", "string")
        .put("enum", JSONArray(listOf("up", "down", "left", "right")))
        .put("description", "Swipe direction. up reveals content below.")

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
        .put(
            "anyOf",
            JSONArray(
                listOf(
                    JSONObject().put("required", JSONArray().put("viewIdResourceName")),
                    JSONObject().put("required", JSONArray().put("text")),
                    JSONObject().put("required", JSONArray().put("description")),
                    JSONObject().put("required", JSONArray().put("treePath")),
                    JSONObject().put("required", JSONArray().put("bounds")),
                    JSONObject().put("required", JSONArray().put("packageName").put("className")),
                ),
            ),
        )

    fun extractReasoning(message: JSONObject, choice: JSONObject? = null): String {
        val parts = mutableListOf<String>()
        fun collect(source: JSONObject?, vararg keys: String) {
            if (source == null) return
            keys.forEach { key ->
                when (val value = source.opt(key)) {
                    is String -> if (value.isNotBlank()) parts += value.trim()
                    is JSONObject -> {
                        value.optString("content").trim().takeIf { it.isNotBlank() }?.let(parts::add)
                        value.optString("text").trim().takeIf { it.isNotBlank() }?.let(parts::add)
                    }
                }
            }
        }
        collect(message, "reasoning_content", "reasoning", "thinking", "reasoning_text")
        collect(choice, "reasoning_content", "reasoning", "thinking")
        return parts.firstOrNull().orEmpty()
    }

    fun extractThought(argumentsJson: String): String = runCatching {
        JSONObject(argumentsJson).optString("thought").trim()
    }.getOrDefault("")

    private fun message(role: String, content: Any): JSONObject =
        JSONObject().put("role", role).put("content", content)
}
