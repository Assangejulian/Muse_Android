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
        val allowed = setOf("action", "packageName", "text", "nodeId", "direction", "milliseconds", "reason", "completeAfter")
        require(json.keys().asSequence().all { it in allowed }) { "Unknown response field" }
        return when (json.getString("action")) {
            "launch_app" -> AgentAction.LaunchApp(json.getString("packageName"))
            "click_text" -> AgentAction.ClickText(json.getString("text"), json.optBoolean("completeAfter", false))
            "click_node" -> AgentAction.ClickNode(json.getInt("nodeId"), json.optBoolean("completeAfter", false))
            "swipe" -> AgentAction.Swipe(json.optString("direction", "up"))
            "input_text" -> AgentAction.InputText(json.getString("text"))
            "back" -> AgentAction.Back
            "home" -> AgentAction.Home
            "wait" -> AgentAction.Wait(json.optLong("milliseconds", 1000).coerceIn(250, 5000))
            "finish" -> AgentAction.Finish(json.optString("reason", "Task completed"))
            "fail" -> AgentAction.Fail(json.optString("reason", "Planner stopped"))
            else -> error("Unknown action")
        }
    }
}
