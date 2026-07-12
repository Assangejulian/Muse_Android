package com.androidagent.app.agent

class ExecutionHarness(private val goal: String) {
    private val query = GoalContract.extractSearchQuery(goal)
    private val milestones = linkedSetOf<String>()
    private val screenTrail = ArrayDeque<String>()
    private val attempts = mutableMapOf<String, Int>()
    private var loopDetected = false

    fun observe(observation: Observation) {
        val fingerprint = fingerprint(observation)
        screenTrail.addLast(fingerprint)
        while (screenTrail.size > 8) screenTrail.removeFirst()
        val trail = screenTrail.toList()
        loopDetected = trail.size >= 4 && trail.takeLast(4).let { it[0] == it[2] && it[1] == it[3] && it[0] != it[1] }
    }

    fun normalize(action: AgentAction): AgentAction = when {
        action is AgentAction.InputText && query != null -> AgentAction.InputText(query)
        else -> action
    }

    fun blockReason(action: AgentAction, observation: Observation): String? {
        val signature = signature(action) ?: return null
        val key = "${fingerprint(observation)}|$signature"
        val count = attempts.getOrDefault(key, 0)
        if (count >= 2) return "same action already attempted twice on this screen"
        if (loopDetected && (action is AgentAction.Home || action is AgentAction.Back || action is AgentAction.LaunchApp)) {
            return "screen cycle detected; navigation action would repeat the loop"
        }
        if (action is AgentAction.InputText && query != null && observation.visibleText().contains(query, true)) {
            return "search query is already present; do not type it again"
        }
        attempts[key] = count + 1
        return null
    }

    fun recordSuccess(action: AgentAction, before: Observation, after: Observation): String {
        when (action) {
            is AgentAction.LaunchApp -> milestones += "app_launched"
            is AgentAction.InputText -> milestones += "query_entered"
            is AgentAction.ClickText, is AgentAction.ClickNode -> {
                if ("query_entered" in milestones && "result_opened" !in milestones) milestones += "result_opened"
                else if ("result_opened" in milestones && "content_opened" !in milestones) milestones += "content_opened"
            }
            else -> Unit
        }
        val changed = fingerprint(before) != fingerprint(after)
        return if (changed) "screen_changed" else "screen_unchanged"
    }

    fun recoveryAction(observation: Observation): AgentAction? {
        if (!loopDetected) return null
        val nodes = observation.nodes
        if (query != null && nodes.any { it.editable }) return AgentAction.InputText(query)
        val searchNode = nodes.firstOrNull {
            it.text.equals("搜索", true) || it.description.contains("搜索", true)
        }
        return searchNode?.let { AgentAction.ClickNode(it.id) } ?: AgentAction.Swipe("up")
    }

    fun context(): String = buildString {
        append("query=").append(query ?: "none")
        append("; milestones=").append(if (milestones.isEmpty()) "none" else milestones.joinToString(","))
        append("; loopDetected=").append(loopDetected)
        append("; rule=never redo completed milestones; input query exactly as provided")
    }

    private fun fingerprint(observation: Observation): String {
        val content = observation.nodes.take(60).joinToString("|") {
            "${it.text.trim()}#${it.description.trim()}#${it.className}#${it.editable}"
        }
        return "${observation.packageName}:${content.hashCode().toUInt().toString(16)}"
    }

    private fun signature(action: AgentAction): String? = when (action) {
        is AgentAction.LaunchApp -> "launch:${action.packageName}"
        is AgentAction.ClickText -> "click_text:${action.text.lowercase()}"
        is AgentAction.ClickNode -> "click_node:${action.nodeId}"
        is AgentAction.InputText -> "input:${action.text.lowercase()}"
        is AgentAction.Swipe -> "swipe:${action.direction}"
        AgentAction.Back -> "back"
        AgentAction.Home -> "home"
        else -> null
    }
}

internal object GoalContract {
    private val latestCreator = Regex("(?:给|搜索|查找)(.+?)(?:的)?最新")
    private val explicitSearch = Regex("(?:搜索|查找)([^，。,.]+)")

    fun extractSearchQuery(goal: String): String? {
        val candidate = latestCreator.find(goal)?.groupValues?.get(1)
            ?: explicitSearch.find(goal)?.groupValues?.get(1)
        return candidate?.trim()?.removePrefix("一下")?.takeIf { it.isNotBlank() }
    }
}

private fun Observation.visibleText(): String = nodes.joinToString(" ") { "${it.text} ${it.description}" }
