package com.androidagent.app.agent

import org.json.JSONObject

object ActionParser {
    fun parse(raw: String, toolName: String? = null): AgentAction {
        val trimmed = raw.trim()
        val start = trimmed.indexOf('{')
        val end = trimmed.lastIndexOf('}')
        require(start >= 0 && end >= start) { "Planner response did not contain an action JSON object" }
        val clean = trimmed.substring(start, end + 1)
        val json = JSONObject(clean)
        val named = json.optString("action").trim()
        val action = when {
            named.isNotBlank() -> named
            !toolName.isNullOrBlank() && toolName != "android_action" -> toolName
            else -> error("Planner response did not name an action")
        }
        // Ignore unknown fields instead of rejecting the whole turn; models often
        // add commentary keys that do not change the executable contract.
        return when (action) {
            "launch_app" -> AgentAction.LaunchApp(json.getString("packageName").also { require(it.isNotBlank()) })
            "click_text" -> AgentAction.ClickText(
                text = json.getString("text").also { require(it.isNotBlank()) },
                predicateId = json.optString("predicateId").ifBlank { null },
            )
            "click_node" -> AgentAction.ClickNode(
                json.getInt("nodeId").also { require(it > 0) },
                parseSelector(json.optJSONObject("selector")),
                json.optString("predicateId").ifBlank { null },
            )
            "tap_point" -> AgentAction.TapPoint(
                json.getInt("x").also { require(it in 0..1000) },
                json.getInt("y").also { require(it in 0..1000) },
            )
            "swipe" -> AgentAction.Swipe(json.getString("direction").also { require(it in setOf("up", "down", "left", "right")) })
            "input_text" -> AgentAction.InputText(
                text = json.getString("text"),
                nodeId = json.optInt("nodeId").takeIf { json.has("nodeId") }?.also { require(it > 0) },
                target = parseSelector(json.optJSONObject("target")),
                mode = runCatching { InputMode.valueOf(json.optString("mode", "REPLACE").uppercase()) }.getOrElse { error("Invalid input mode") },
                submit = json.optBoolean("submit", false),
                predicateId = json.optString("predicateId").ifBlank { null },
            )
            "submit_input" -> AgentAction.SubmitInput(
                json.optInt("nodeId").takeIf { json.has("nodeId") }?.also { require(it > 0) },
                parseSelector(json.optJSONObject("target")),
                json.optString("predicateId").ifBlank { null },
            )
            "ensure_toggle" -> AgentAction.EnsureToggle(
                json.getInt("nodeId").also { require(it > 0) },
                json.getBoolean("desired"),
                parseSelector(json.optJSONObject("selector")),
                json.optString("predicateId").ifBlank { null },
            )
            "bind_predicate", "inspect_element" -> AgentAction.BindPredicate(
                predicateId = json.getString("predicateId").also { require(it.isNotBlank()) },
                nodeId = json.optInt("nodeId").takeIf { json.has("nodeId") }?.also { require(it > 0) },
                selector = parseSelector(json.optJSONObject("selector")),
            )
            "find_nodes" -> AgentAction.FindNodes(parseNodeQuery(json))
            "read_node" -> AgentAction.ReadNode(
                nodeId = json.optInt("nodeId").takeIf { json.has("nodeId") }?.also { require(it > 0) },
                selector = parseSelector(json.optJSONObject("selector")),
            ).also { require(it.nodeId != null || it.selector != null) { "read_node requires nodeId or selector" } }
            "scroll_until" -> AgentAction.ScrollUntil(
                direction = json.getString("direction").also { require(it in setOf("up", "down", "left", "right")) },
                query = parseNodeQuery(json),
                maxSwipes = json.optInt("maxSwipes", 6).also { require(it in 1..12) },
            )
            "wait_until" -> {
                val query = runCatching { parseNodeQuery(json) }.getOrNull()
                val milliseconds = json.optLong("milliseconds", if (query == null) 1_000L else 3_000L)
                if (query == null) {
                    require(milliseconds in 250L..5_000L) { "wait duration out of range" }
                    AgentAction.Wait(milliseconds)
                } else {
                    require(milliseconds in 250L..8_000L) { "wait_until duration out of range" }
                    AgentAction.WaitUntil(query, milliseconds)
                }
            }
            "terminal" -> AgentAction.Terminal(
                command = json.getString("command").also { require(it.isNotBlank() && it.length <= 16_000) },
                timeoutMillis = json.optLong("timeoutMillis", 5_000L).also { require(it in 250L..30_000L) },
            )
            "back" -> AgentAction.Back
            "home" -> AgentAction.Home
            "wait" -> AgentAction.Wait(json.optLong("milliseconds", 1000).also { require(it in 250L..5000L) })
            "finish" -> AgentAction.Finish(json.getString("reason").also { require(it.isNotBlank()) })
            "fail" -> AgentAction.Fail(json.getString("reason").also { require(it.isNotBlank()) })
            else -> error("Unknown action")
        }
    }

    private fun parseSelector(json: JSONObject?): ElementSelector? {
        return ElementSelectorJson.parse(json)
    }

    private fun parseNodeQuery(json: JSONObject): NodeQuery {
        val source = json.optJSONObject("query") ?: json
        val query = NodeQuery(
            text = source.optString("text").trim().ifBlank { null },
            description = source.optString("description").trim().ifBlank { null },
            viewId = source.optString("viewId").ifBlank { source.optString("viewIdResourceName") }.trim().ifBlank { null },
            className = source.optString("className").trim().ifBlank { null },
            clickable = if (source.has("clickable")) source.getBoolean("clickable") else null,
            checked = if (source.has("checked")) source.getBoolean("checked") else null,
            selected = if (source.has("selected")) source.getBoolean("selected") else null,
            scrollable = if (source.has("scrollable")) source.getBoolean("scrollable") else null,
            enabled = if (source.has("enabled")) source.getBoolean("enabled") else null,
            visible = if (source.has("visible")) source.getBoolean("visible") else null,
            limit = source.optInt("limit", json.optInt("limit", NodeQuery.DEFAULT_LIMIT))
                .coerceIn(1, NodeQuery.MAX_LIMIT),
        )
        require(query.hasConstraint()) { "query requires at least one identifying field" }
        return query
    }
}
