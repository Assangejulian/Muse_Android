package com.androidagent.app.agent

data class UiNodeSnapshot(
    val id: Int,
    val text: String,
    val description: String,
    val className: String,
    val clickable: Boolean,
    val editable: Boolean,
    val bounds: String,
)

data class Observation(
    val packageName: String,
    val nodes: List<UiNodeSnapshot>,
) {
    fun compactText(): String = buildString {
        appendLine("package=$packageName")
        nodes.take(80).forEach { node ->
            append("#${node.id} ${node.className}")
            if (node.text.isNotBlank()) append(" text=${node.text.take(80)}")
            if (node.description.isNotBlank()) append(" description=${node.description.take(80)}")
            append(" clickable=${node.clickable} editable=${node.editable} bounds=${node.bounds}")
            appendLine()
        }
    }.take(12_000)
}

sealed interface AgentAction {
    data class LaunchApp(val packageName: String) : AgentAction
    data class ClickText(val text: String, val completeAfter: Boolean = false) : AgentAction
    data class ClickNode(val nodeId: Int, val completeAfter: Boolean = false) : AgentAction
    data class Swipe(val direction: String) : AgentAction
    data class InputText(val text: String) : AgentAction
    data class Wait(val milliseconds: Long) : AgentAction
    data object Back : AgentAction
    data object Home : AgentAction
    data class Finish(val reason: String) : AgentAction
    data class Fail(val reason: String) : AgentAction
}

data class AgentUiState(
    val running: Boolean = false,
    val accessibilityConnected: Boolean = false,
    val step: Int = 0,
    val status: String = "Idle",
    val currentPackage: String = "",
    val logs: List<String> = emptyList(),
)
