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

/** Stable identity captured from one live observation for a predicate binding. */
data class BoundElementIdentity(
    val packageName: String,
    val windowId: Int?,
    val viewIdResourceName: String?,
    val stableKey: String?,
    val treePath: List<Int>?,
    val className: String,
    val initialBounds: String?,
    val initialTextHash: String,
    val initialDescriptionHash: String,
) {
    companion object {
        fun from(node: UiNodeSnapshot): BoundElementIdentity = BoundElementIdentity(
            packageName = node.packageName,
            windowId = node.windowId,
            viewIdResourceName = node.viewId.takeIf(String::isNotBlank),
            stableKey = node.stableKey.takeIf(String::isNotBlank),
            treePath = node.treePath,
            className = node.className,
            initialBounds = node.bounds.takeIf(String::isNotBlank),
            initialTextHash = digest(node.text),
            initialDescriptionHash = digest(node.description),
        )

        private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .take(8)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}

/** Result of resolving a binding against a fresh accessibility observation. */
sealed interface IdentityResolution {
    data class Found(val node: UiNodeSnapshot) : IdentityResolution
    /** The bound window is still present, but the bound node is absent. */
    data object MissingInSameWindow : IdentityResolution
    /** The bound window disappeared while the package remained foreground. */
    data object BoundWindowGone : IdentityResolution
    data class Ambiguous(val count: Int) : IdentityResolution
    data object PackageChanged : IdentityResolution
    /** A same-package replacement window contains the bound structure. */
    data object WindowRecreated : IdentityResolution
    data object IdentityInvalidated : IdentityResolution
}

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

        // Selectors are re-located by stable evidence in priority order.  A
        // captured bounds value is deliberately only a fallback: a node may
        // move while retaining the same text/class or resource id.
        fun refine(candidates: List<UiNodeSnapshot>): List<UiNodeSnapshot> {
            var result = candidates
            selector.treePath?.let { path ->
                val byPath = result.filter { it.treePath == path }
                if (byPath.isNotEmpty()) result = byPath
            }
            selector.bounds?.let { bounds ->
                val byBounds = result.filter { it.bounds == bounds }
                if (byBounds.isNotEmpty()) result = byBounds
            }
            return result
        }

        selector.viewIdResourceName?.let { viewId ->
            val byViewId = scoped.filter { it.viewId == viewId }
            if (byViewId.isEmpty()) return emptyList()
            if (byViewId.size == 1) return byViewId
            if (byViewId.size > 1) {
                val narrowed = when {
                    selector.text != null && selector.className != null ->
                        byViewId.filter { it.text == selector.text && it.className == selector.className }
                    selector.text != null -> byViewId.filter { it.text == selector.text }
                    selector.description != null && selector.className != null ->
                        byViewId.filter { it.description == selector.description && it.className == selector.className }
                    selector.description != null -> byViewId.filter { it.description == selector.description }
                    else -> refine(byViewId)
                }
                return refine(if (narrowed.isNotEmpty()) narrowed else byViewId)
            }
        }

        if (selector.text != null && selector.className != null) {
            val byTextAndClass = scoped.filter { it.text == selector.text && it.className == selector.className }
            if (byTextAndClass.isNotEmpty()) return refine(byTextAndClass)
        }
        if (selector.description != null && selector.className != null) {
            val byDescriptionAndClass = scoped.filter {
                it.description == selector.description && it.className == selector.className
            }
            if (byDescriptionAndClass.isNotEmpty()) return refine(byDescriptionAndClass)
        }
        selector.treePath?.let { path ->
            val byPath = scoped.filter { it.treePath == path }
            if (byPath.isNotEmpty()) return selector.bounds?.let { bounds ->
                val byBounds = byPath.filter { it.bounds == bounds }
                if (byBounds.isNotEmpty()) byBounds else byPath
            } ?: byPath
        }
        selector.bounds?.let { bounds ->
            val byBounds = scoped.filter { it.bounds == bounds }
            if (byBounds.isNotEmpty()) return byBounds
        }
        if (selector.packageName != null && selector.className != null) {
            val byPackageAndClass = scoped.filter { it.className == selector.className }
            if (byPackageAndClass.isNotEmpty()) return byPackageAndClass
        }
        selector.text?.let { text ->
            val byText = scoped.filter { it.text == text }
            if (byText.isNotEmpty()) return byText
        }
        selector.description?.let { description ->
            val byDescription = scoped.filter { it.description == description }
            if (byDescription.isNotEmpty()) return byDescription
        }
        return emptyList()
    }

    /** Compatibility list view. Use [resolveIdentity] when the reason matters. */
    fun matchingNodes(observation: Observation, identity: BoundElementIdentity): List<UiNodeSnapshot> =
        when (val resolution = resolveIdentity(observation, identity)) {
            is IdentityResolution.Found -> listOf(resolution.node)
            is IdentityResolution.Ambiguous -> candidatesForIdentity(observation, identity).take(resolution.count)
            else -> emptyList()
        }

    /**
     * Resolve a bound identity without silently replacing a lost resource id
     * with an unrelated node of the same class.
     */
    fun resolveIdentity(observation: Observation, identity: BoundElementIdentity): IdentityResolution {
        if (identity.packageName.isNotBlank() && observation.packageName.isBlank()) {
            return IdentityResolution.IdentityInvalidated
        }
        if (identity.packageName.isNotBlank() && observation.packageName.isNotBlank() && observation.packageName != identity.packageName) {
            return IdentityResolution.PackageChanged
        }
        val packageNodes = observation.nodes.filter { node ->
            identity.packageName.isBlank() || node.packageName == identity.packageName
        }
        if (packageNodes.isEmpty()) {
            return if (identity.windowId == null && observation.packageName == identity.packageName) {
                IdentityResolution.MissingInSameWindow
            } else if (identity.windowId != null && observation.packageName == identity.packageName) {
                val windowStillBelongsToPackage = observation.windowPackages[identity.windowId]?.let { it == identity.packageName }
                    ?: (identity.windowId in observation.windowIds)
                if (windowStillBelongsToPackage) {
                    IdentityResolution.MissingInSameWindow
                } else {
                    IdentityResolution.BoundWindowGone
                }
            } else {
                IdentityResolution.IdentityInvalidated
            }
        }

        if (identity.windowId != null) {
            val sameWindow = packageNodes.filter { it.windowId == identity.windowId }
            if (sameWindow.isEmpty()) {
                val windowStillBelongsToPackage = observation.windowPackages[identity.windowId]?.let { it == identity.packageName }
                    ?: (identity.windowId in observation.windowIds)
                if (windowStillBelongsToPackage) return IdentityResolution.MissingInSameWindow

                // A replacement activity normally exposes the same stable
                // structure under a fresh window id. That is not evidence that
                // the old element disappeared; it is an identity transition.
                if (packageNodes.any { structurallyMatchesIgnoringWindow(it, identity) }) {
                    return IdentityResolution.WindowRecreated
                }
                return IdentityResolution.BoundWindowGone
            }
            return resolveWithinContext(sameWindow, identity)
        }
        return resolveWithinContext(packageNodes, identity)
    }

    private fun resolveWithinContext(candidates: List<UiNodeSnapshot>, identity: BoundElementIdentity): IdentityResolution {
        val scoped = if (identity.viewIdResourceName != null) {
            val byViewId = candidates.filter { it.viewId == identity.viewIdResourceName }
            // A captured view id is a hard identity. Its disappearance must
            // not fall back to an arbitrary same-class node.
            if (byViewId.isEmpty()) return IdentityResolution.MissingInSameWindow
            byViewId
        } else {
            candidates
        }
        val narrowed = narrowIdentityCandidates(scoped, identity)
        return when {
            narrowed.size == 1 -> IdentityResolution.Found(narrowed.single())
            narrowed.size > 1 -> IdentityResolution.Ambiguous(narrowed.size)
            else -> if (identity.viewIdResourceName == null) {
                IdentityResolution.MissingInSameWindow
            } else {
                IdentityResolution.IdentityInvalidated
            }
        }
    }

    private fun candidatesForIdentity(observation: Observation, identity: BoundElementIdentity): List<UiNodeSnapshot> {
        val packageNodes = observation.nodes.filter { identity.packageName.isBlank() || it.packageName == identity.packageName }
        val windowNodes = identity.windowId?.let { window -> packageNodes.filter { it.windowId == window } } ?: packageNodes
        val viewNodes = identity.viewIdResourceName?.let { id -> windowNodes.filter { it.viewId == id } } ?: windowNodes
        return narrowIdentityCandidates(viewNodes, identity)
    }

    private fun narrowIdentityCandidates(candidates: List<UiNodeSnapshot>, identity: BoundElementIdentity): List<UiNodeSnapshot> {
        var narrowed = candidates
        identity.stableKey?.let { key ->
            val byStableKey = narrowed.filter { it.stableKey == key }
            if (byStableKey.isNotEmpty()) narrowed = byStableKey
        }
        identity.treePath?.let { path ->
            val byPath = narrowed.filter { it.treePath == path }
            if (byPath.isNotEmpty()) narrowed = byPath
        }
        if (identity.className.isNotBlank()) {
            val byClass = narrowed.filter { it.className == identity.className }
            if (byClass.isNotEmpty()) narrowed = byClass
        }
        val hasStrongIdentity = identity.viewIdResourceName != null || identity.stableKey != null || identity.treePath != null
        if (narrowed.size > 1 || !hasStrongIdentity) {
            identity.initialBounds?.let { bounds ->
                val parsed = parseBounds(bounds)
                if (parsed != null) {
                    val byBounds = narrowed.filter { node ->
                        val current = parseBounds(node.bounds) ?: return@filter false
                        kotlin.math.abs(current[0] - parsed[0]) <= BOUNDS_DRIFT_PX &&
                            kotlin.math.abs(current[1] - parsed[1]) <= BOUNDS_DRIFT_PX &&
                            kotlin.math.abs(current[2] - parsed[2]) <= BOUNDS_DRIFT_PX &&
                            kotlin.math.abs(current[3] - parsed[3]) <= BOUNDS_DRIFT_PX
                    }
                    if (byBounds.isNotEmpty()) narrowed = byBounds
                    else if (!hasStrongIdentity) narrowed = emptyList()
                }
            }
        }
        return narrowed
    }

    /** True absence is only established when the identity's package/window context is retained. */
    fun disappearanceResolution(observation: Observation, identity: BoundElementIdentity): IdentityResolution =
        when (val resolution = resolveIdentity(observation, identity)) {
            IdentityResolution.IdentityInvalidated -> IdentityResolution.IdentityInvalidated
            IdentityResolution.MissingInSameWindow -> IdentityResolution.MissingInSameWindow
            else -> resolution
        }

    private fun structurallyMatchesIgnoringWindow(node: UiNodeSnapshot, identity: BoundElementIdentity): Boolean {
        if (identity.packageName.isNotBlank() && node.packageName.isNotBlank() && node.packageName != identity.packageName) return false
        if (identity.viewIdResourceName != null && node.viewId != identity.viewIdResourceName) return false
        if (identity.stableKey != null && node.stableKey != identity.stableKey) return false
        if (identity.className.isNotBlank() && node.className != identity.className) return false
        if (identity.treePath != null && node.treePath != identity.treePath) return false
        return identity.viewIdResourceName != null || identity.stableKey != null || identity.treePath != null
    }

    fun resolve(observation: Observation, nodeId: Int?, selector: ElementSelector?): UiNodeSnapshot? {
        if (selector != null) {
            // Once a selector is supplied, the stale numeric id is never a
            // fallback.  Zero or multiple matches are both unsafe.
            return matchingNodes(observation, selector).singleOrNull()
        }
        return nodeId?.let { id -> observation.nodes.firstOrNull { it.id == id } }
    }

    private fun parseBounds(bounds: String): IntArray? = runCatching {
        bounds.split(',').map(String::toInt).toIntArray()
    }.getOrNull()?.takeIf { it.size == 4 }

    private const val BOUNDS_DRIFT_PX = 24
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
    /** Window ids observed in this snapshot, including windows with no meaningful nodes. */
    val windowIds: Set<Int> = nodes.mapNotNull { it.windowId }.toSet(),
    /** Optional package ownership for windows that have no emitted nodes. */
    val windowPackages: Map<Int, String> = emptyMap(),
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
    data class ClickText(val text: String, val predicateId: String? = null) : AgentAction
    data class ClickNode(val nodeId: Int, val selector: ElementSelector? = null, val predicateId: String? = null) : AgentAction
    data class TapPoint(val x: Int, val y: Int) : AgentAction
    data class Swipe(val direction: String) : AgentAction
    data class InputText(
        val text: String,
        val nodeId: Int? = null,
        val target: ElementSelector? = null,
        val mode: InputMode = InputMode.REPLACE,
        val submit: Boolean = false,
        val predicateId: String? = null,
    ) : AgentAction
    data class SubmitInput(val nodeId: Int? = null, val target: ElementSelector? = null, val predicateId: String? = null) : AgentAction
    data class EnsureToggle(val nodeId: Int, val desired: Boolean, val selector: ElementSelector? = null, val predicateId: String? = null) : AgentAction
    data class BindPredicate(val predicateId: String, val nodeId: Int? = null, val selector: ElementSelector? = null) : AgentAction
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
