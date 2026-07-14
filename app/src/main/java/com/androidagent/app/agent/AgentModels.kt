package com.androidagent.app.agent

import java.security.MessageDigest

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
    val treePath: List<Int>? = null,
    val enabled: Boolean = true,
    val focused: Boolean = false,
    val checked: Boolean? = null,
    val selected: Boolean = false,
    val scrollable: Boolean = false,
    val packageName: String = "",
    val isInputMethod: Boolean = false,
    val visible: Boolean = true,
    val password: Boolean = false,
    val windowId: Int? = null,
) {
    val viewIdResourceName: String get() = viewId
}

data class ElementSelector(
    val packageName: String? = null,
    val viewIdResourceName: String? = null,
    val text: String? = null,
    val description: String? = null,
    val className: String? = null,
    val treePath: List<Int>? = null,
    val bounds: String? = null,
)

/** Resolves an element against a fresh observation without trusting a stale numeric id. */
object NodeSelector {
    fun from(snapshot: UiNodeSnapshot): ElementSelector = ElementSelector(
        packageName = snapshot.packageName.takeIf(String::isNotBlank),
        viewIdResourceName = snapshot.viewId.takeIf(String::isNotBlank),
        text = snapshot.text.takeIf(String::isNotBlank),
        description = snapshot.description.takeIf(String::isNotBlank),
        className = snapshot.className.takeIf(String::isNotBlank),
        treePath = snapshot.treePath,
        bounds = snapshot.bounds.takeIf(String::isNotBlank),
    )

    fun matchingNodes(observation: Observation, selector: ElementSelector): List<UiNodeSnapshot> {
        val scoped = observation.nodes.filter { node ->
            selector.packageName.isNullOrBlank() || node.packageName == selector.packageName
        }
        selector.viewIdResourceName?.let { viewId ->
            val matches = scoped.filter { it.viewId == viewId }
            if (matches.isNotEmpty()) return matches
        }
        if (selector.text != null && selector.className != null) {
            val matches = scoped.filter { it.text == selector.text && it.className == selector.className }
            if (matches.isNotEmpty()) return matches
        }
        if (selector.description != null && selector.className != null) {
            val matches = scoped.filter { it.description == selector.description && it.className == selector.className }
            if (matches.isNotEmpty()) return matches
        }
        selector.treePath?.let { path ->
            val matches = scoped.filter { it.treePath == path }
            if (matches.isNotEmpty()) return matches
        }
        selector.bounds?.let { bounds ->
            val matches = scoped.filter { it.bounds == bounds }
            if (matches.isNotEmpty()) return matches
        }
        return scoped
    }

    fun resolve(observation: Observation, nodeId: Int?, selector: ElementSelector?): UiNodeSnapshot? {
        val selected = selector?.let { matchingNodes(observation, it) }.orEmpty()
        if (selected.size == 1) return selected.single()
        if (selected.size > 1) return null
        return nodeId?.let { id -> observation.nodes.firstOrNull { it.id == id } }
    }
}

data class Observation(
    val packageName: String,
    val nodes: List<UiNodeSnapshot>,
    val imeVisible: Boolean = false,
) {
    val observationId: String get() = stateFingerprint()

    fun stateFingerprint(): String {
        // Text is normalized so clock-like timestamps do not churn the loop detector;
        // stable ids, classes, bounds, and actionability remain part of the token.
        val stableContent = nodes.joinToString("|") { node ->
            val normalizedText = normalizeDynamicText(node.text)
            val normalizedDescription = normalizeDynamicText(node.description)
            "${node.packageName}:${node.viewId}:${node.className}:$normalizedText:$normalizedDescription:" +
                "${node.visible}:${node.enabled}:${node.clickable}:${node.editable}:${node.focused}:" +
                "${node.checked}:${node.selected}:${node.scrollable}:${node.bounds}:${node.treePath?.joinToString("/")}"
        }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$packageName:$imeVisible:$stableContent".toByteArray())
            .take(12)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return "$packageName:$digest"
    }

    fun visibleText(): String = nodes.asSequence()
        .filter { it.visible && !it.password && !it.isInputMethod }
        .joinToString(" ") { "${it.text} ${it.description}" }

    fun compactText(): String = buildString {
        appendLine("package=$packageName")
        appendLine("imeVisible=$imeVisible")
        prioritizedNodes().take(120).forEach { node ->
            append("#${node.id} ${node.className}")
            if (node.text.isNotBlank() && !node.password) append(" text=${node.text.take(120)}")
            if (node.description.isNotBlank() && !node.password) append(" description=${node.description.take(120)}")
            append(" clickable=${node.clickable} editable=${node.editable} bounds=${node.bounds}")
            if (node.packageName.isNotBlank() && node.packageName != packageName) append(" package=${node.packageName}")
            if (node.viewId.isNotBlank()) append(" viewId=${node.viewId}")
            append(" key=${node.stableKey} enabled=${node.enabled} focused=${node.focused}")
            node.checked?.let { append(" checked=$it") }
            if (node.selected) append(" selected=true")
            if (node.scrollable) append(" scrollable=true")
            appendLine()
        }
    }.take(18_000)

    private fun prioritizedNodes(): List<UiNodeSnapshot> = nodes
        .asSequence()
        .filter { it.visible && !it.password && !it.isInputMethod }
        .sortedWith(
            compareByDescending<UiNodeSnapshot> { node ->
                when {
                    node.focused -> 100
                    node.editable -> 90
                    node.clickable -> 80
                    node.checked != null -> 70
                    node.scrollable -> 60
                    node.text.isNotBlank() || node.description.isNotBlank() -> 40
                    else -> 0
                }
            }.thenBy { node ->
                node.bounds.substringAfter(',').substringBefore(',').toIntOrNull() ?: Int.MAX_VALUE
            },
        )
        .toList()

    private fun normalizeDynamicText(value: String): String = value
        .trim()
        .replace(Regex("\\b\\d{1,4}([:/.-]\\d{1,4}){1,3}\\b"), "<dynamic>")
        .take(120)
}

sealed interface AgentAction {
    data class LaunchApp(val packageName: String) : AgentAction
    data class ClickText(val text: String) : AgentAction
    data class ClickNode(val nodeId: Int, val selector: ElementSelector? = null) : AgentAction
    data class TapPoint(val x: Int, val y: Int) : AgentAction
    data class Swipe(val direction: String) : AgentAction
    data class InputText(
        val text: String,
        val nodeId: Int? = null,
        val target: ElementSelector? = null,
        val mode: InputMode = InputMode.REPLACE,
        val submit: Boolean = false,
    ) : AgentAction
    data class SubmitInput(val nodeId: Int? = null, val target: ElementSelector? = null) : AgentAction
    data class EnsureToggle(val nodeId: Int, val desired: Boolean, val selector: ElementSelector? = null) : AgentAction
    data class Wait(val milliseconds: Long) : AgentAction
    data object Back : AgentAction
    data object Home : AgentAction
    data class Finish(val reason: String) : AgentAction
    data class Fail(val reason: String) : AgentAction
}

enum class InputMode { REPLACE, APPEND, CLEAR }

data class AgentUiState(
    val running: Boolean = false,
    val accessibilityConnected: Boolean = false,
    val step: Int = 0,
    val maxSteps: Int = 24,
    val status: String = "Idle",
    val goal: String = "",
    val currentAction: String = "",
    val outcome: String = "",
    val currentPackage: String = "",
    val logs: List<String> = emptyList(),
)
