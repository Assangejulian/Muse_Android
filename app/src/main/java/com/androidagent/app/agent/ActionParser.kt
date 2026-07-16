package com.androidagent.app.agent

import org.json.JSONObject

object ActionParser {
    fun parse(raw: String): AgentAction {
        val trimmed = raw.trim()
        val start = trimmed.indexOf('{')
        val end = trimmed.lastIndexOf('}')
        require(start >= 0 && end >= start) { "Planner response did not contain an action JSON object" }
        val clean = trimmed.substring(start, end + 1)
        val json = JSONObject(clean)
        val action = json.getString("action")
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
}
