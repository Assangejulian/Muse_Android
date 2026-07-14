package com.androidagent.app.agent

import java.security.MessageDigest
import org.json.JSONObject

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

object ElementSelectorValidation {
    private val boundsPattern = Regex("^-?\\d+,-?\\d+,-?\\d+,-?\\d+$")

    fun validate(selector: ElementSelector): Result<Unit> = runCatching {
        require(selector.packageName?.isNotBlank() != false) { "selector.packageName must not be blank" }
        require(selector.viewIdResourceName?.isNotBlank() != false) { "selector.viewIdResourceName must not be blank" }
        require(selector.text?.isNotBlank() != false) { "selector.text must not be blank" }
        require(selector.description?.isNotBlank() != false) { "selector.description must not be blank" }
        require(selector.className?.isNotBlank() != false) { "selector.className must not be blank" }
        require(selector.treePath == null || selector.treePath.isNotEmpty()) { "selector.treePath must not be empty" }
        require(selector.treePath == null || selector.treePath.all { it >= 0 }) { "selector.treePath contains a negative index" }
        require(selector.bounds == null || boundsPattern.matches(selector.bounds)) { "selector.bounds has invalid format" }
        selector.bounds?.let {
            val values = it.split(',').map(String::toInt)
            require(values[2] > values[0] && values[3] > values[1]) { "selector.bounds must have positive size" }
        }
        require(
            selector.viewIdResourceName != null || selector.text != null || selector.description != null ||
                selector.treePath != null || selector.bounds != null || (selector.packageName != null && selector.className != null),
        ) { "selector must include a real identifying field" }
        require(selector.className == null || selector.text != null || selector.description != null || selector.viewIdResourceName != null || selector.treePath != null || selector.bounds != null || selector.packageName != null) {
            "className cannot identify a selector by itself"
        }
    }
}

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
        if (ElementSelectorValidation.validate(selector).isFailure) return emptyList()
        val scoped = observation.nodes.filter { node ->
            selector.packageName.isNullOrBlank() || node.packageName == selector.packageName
        }
        var candidates = scoped
        selector.viewIdResourceName?.let { viewId ->
            candidates = candidates.filter { it.viewId == viewId }
            if (candidates.isEmpty()) return emptyList()
            if (candidates.size == 1) return candidates
        }
        selector.text?.let { text ->
            candidates = candidates.filter { it.text == text }
            if (candidates.size <= 1) return candidates
        }
        selector.description?.let { description ->
            candidates = candidates.filter { it.description == description }
            if (candidates.size <= 1) return candidates
        }
        selector.className?.let { className ->
            candidates = candidates.filter { it.className == className }
            if (candidates.size <= 1) return candidates
        }
        selector.treePath?.let { path ->
            candidates = candidates.filter { it.treePath == path }
            if (candidates.size <= 1) return candidates
        }
        selector.bounds?.let { bounds -> candidates = candidates.filter { it.bounds == bounds } }
        return candidates
    }

    fun resolve(observation: Observation, nodeId: Int?, selector: ElementSelector?): UiNodeSnapshot? {
        if (selector != null) {
            // Once a selector is supplied, the stale numeric id is never a
            // fallback.  Zero or multiple matches are both unsafe.
            return matchingNodes(observation, selector).singleOrNull()
        }
        return nodeId?.let { id -> observation.nodes.firstOrNull { it.id == id } }
    }
}

/** Shared strict JSON decoding for selectors used by actions and plan predicates. */
object ElementSelectorJson {
    private val allowedFields = setOf("packageName", "viewIdResourceName", "text", "description", "className", "treePath", "bounds")

    fun parse(json: JSONObject?): ElementSelector? {
        if (json == null) return null
        require(json.keys().asSequence().all { it in allowedFields }) { "Unexpected selector field" }
        val path = json.optJSONArray("treePath")?.let { array ->
            buildList { for (index in 0 until array.length()) add(array.getInt(index)) }
        }
        val selector = ElementSelector(
            packageName = json.optString("packageName").ifBlank { null },
            viewIdResourceName = json.optString("viewIdResourceName").ifBlank { null },
            text = json.optString("text").ifBlank { null },
            description = json.optString("description").ifBlank { null },
            className = json.optString("className").ifBlank { null },
            treePath = path,
            bounds = json.optString("bounds").ifBlank { null },
        )
        ElementSelectorValidation.validate(selector).getOrThrow()
        return selector
    }
}

data class ActionExecutionResult(
    val success: Boolean,
    val status: String,
    val detail: String = "",
)

object InputActionResultPolicy {
    fun resolve(textSet: Boolean, textVerified: Boolean, submitRequested: Boolean, submitSucceeded: Boolean): ActionExecutionResult = when {
        !textSet -> ActionExecutionResult(false, "text_set_failed", "text_set failed")
        !textVerified -> ActionExecutionResult(false, "text_verification_failed", "text verification failed")
        submitRequested && !submitSucceeded -> ActionExecutionResult(false, "submit_failed", "submit failed")
        submitRequested -> ActionExecutionResult(true, "text_set_and_submitted", "text_set, text_verified, and submit succeeded")
        else -> ActionExecutionResult(true, "text_verified", "text_set and verified")
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
