package com.androidagent.app.agent

data class UiNodeSnapshot(
    val id: Int,
    val text: String,
    val description: String,
    val className: String,
    val clickable: Boolean,
    val editable: Boolean,
    val bounds: String,
    val stableKey: String = "",
    val viewId: String = "",
    val treePath: String = "",
    val enabled: Boolean = true,
    val focused: Boolean = false,
    val checked: Boolean? = null,
    val selected: Boolean = false,
    val scrollable: Boolean = false,
    val packageName: String = "",
    val isInputMethod: Boolean = false,
)

data class Observation(
    val packageName: String,
    val nodes: List<UiNodeSnapshot>,
    val imeVisible: Boolean = false,
) {
    val observationId: String get() = stateFingerprint()

    fun stateFingerprint(): String {
        val stableContent = nodes.take(100).joinToString("|") { node ->
            val text = node.text.replace(Regex("\\d+"), "#").take(80)
            val description = node.description.replace(Regex("\\d+"), "#").take(80)
            "${node.viewId}:${node.className}:$text:$description:${node.enabled}:${node.focused}:${node.checked}:${node.selected}:${node.bounds}"
        }
        return "$packageName:$imeVisible:${stableContent.hashCode().toUInt().toString(16)}"
    }

    fun visibleText(): String = nodes.joinToString(" ") { "${it.text} ${it.description}" }

    fun compactText(): String = buildString {
        appendLine("package=$packageName")
        appendLine("imeVisible=$imeVisible")
        nodes.take(80).forEach { node ->
            append("#${node.id} ${node.className}")
            if (node.text.isNotBlank()) append(" text=${node.text.take(80)}")
            if (node.description.isNotBlank()) append(" description=${node.description.take(80)}")
            append(" clickable=${node.clickable} editable=${node.editable} bounds=${node.bounds}")
            if (node.packageName.isNotBlank() && node.packageName != packageName) append(" package=${node.packageName}")
            if (node.viewId.isNotBlank()) append(" viewId=${node.viewId}")
            append(" key=${node.stableKey} focused=${node.focused}")
            node.checked?.let { append(" checked=$it") }
            appendLine()
        }
    }.take(12_000)
}

sealed interface AgentAction {
    data class LaunchApp(val packageName: String) : AgentAction
    data class ClickText(val text: String) : AgentAction
    data class ClickNode(val nodeId: Int) : AgentAction
    data class TapPoint(val x: Int, val y: Int) : AgentAction
    data class Swipe(val direction: String) : AgentAction
    data class InputText(val text: String, val nodeId: Int? = null) : AgentAction
    data class SubmitInput(val nodeId: Int? = null) : AgentAction
    data class EnsureToggle(val nodeId: Int, val desired: Boolean) : AgentAction
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
