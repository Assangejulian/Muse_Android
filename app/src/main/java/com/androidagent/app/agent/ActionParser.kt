package com.androidagent.app.agent

import org.json.JSONObject

object ActionParser {
    fun parse(raw: String): AgentAction {
        val clean = raw.substringAfter("```json", raw).substringAfter("```", raw).substringBeforeLast("```").trim()
        val json = JSONObject(clean)
        val allowed = setOf("action", "packageName", "text", "nodeId", "direction", "milliseconds", "reason")
        require(json.keys().asSequence().all { it in allowed }) { "Unknown response field" }
        return when (json.getString("action")) {
            "launch_app" -> AgentAction.LaunchApp(json.getString("packageName"))
            "click_text" -> AgentAction.ClickText(json.getString("text"))
            "click_node" -> AgentAction.ClickNode(json.getInt("nodeId"))
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
