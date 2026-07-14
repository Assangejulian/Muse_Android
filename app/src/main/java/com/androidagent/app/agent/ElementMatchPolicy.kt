package com.androidagent.app.agent

import java.util.Locale

/** Stable, ordered matching rules for re-locating a node after a tree refresh. */
object ElementMatchPolicy {
    fun score(
        snapshot: UiNodeSnapshot,
        viewId: String,
        text: String,
        description: String,
        className: String,
        bounds: String,
        editable: Boolean,
        clickable: Boolean,
    ): Int = score(snapshot, snapshot.packageName, viewId, text, description, className, bounds, editable, clickable)

    fun score(
        snapshot: UiNodeSnapshot,
        viewId: String,
        text: String,
        description: String,
        className: String,
        bounds: String,
        editable: Boolean,
        clickable: Boolean,
        treePath: List<Int>?,
    ): Int = score(snapshot, snapshot.packageName, viewId, text, description, className, bounds, editable, clickable, treePath)

    fun score(
        snapshot: UiNodeSnapshot,
        packageName: String,
        viewId: String,
        text: String,
        description: String,
        className: String,
        bounds: String,
        editable: Boolean,
        clickable: Boolean,
        treePath: List<Int>? = null,
    ): Int {
        if (snapshot.packageName.isNotBlank() && packageName.isNotBlank() && snapshot.packageName != packageName) return 0
        var score = 0
        if (snapshot.packageName.isNotBlank() && snapshot.packageName == packageName) score += 8
        if (snapshot.viewId.isNotBlank() && snapshot.viewId == viewId) score += 100
        if (snapshot.text.isNotBlank() && snapshot.text == text && snapshot.className == className) score += 60
        if (snapshot.description.isNotBlank() && snapshot.description == description && snapshot.className == className) score += 55
        if (snapshot.className.isNotBlank() && snapshot.className == className) score += 8
        if (snapshot.treePath != null && snapshot.treePath == treePath) score += 25
        if (snapshot.bounds == bounds) score += 12
        if (editable == snapshot.editable) score += 2
        if (clickable == snapshot.clickable) score += 2
        return score
    }

    fun minimumScore(snapshot: UiNodeSnapshot): Int = when {
        snapshot.viewId.isNotBlank() -> 100
        snapshot.text.isNotBlank() || snapshot.description.isNotBlank() -> 60
        snapshot.treePath != null -> 25
        else -> 12
    }

    fun score(snapshot: UiNodeSnapshot, selector: ElementSelector): Int = score(
        snapshot = snapshot,
        packageName = selector.packageName.orEmpty(),
        viewId = selector.viewIdResourceName.orEmpty(),
        text = selector.text.orEmpty(),
        description = selector.description.orEmpty(),
        className = selector.className.orEmpty(),
        bounds = selector.bounds.orEmpty(),
        editable = snapshot.editable,
        clickable = snapshot.clickable,
        treePath = selector.treePath,
    )
}

enum class TargetHintResult { MATCH, NO_MATCH, AMBIGUOUS }

/** Conservative hint matching used only for conflict checks and candidate scoring. */
object TargetHintMatcher {
    fun semanticallyEquivalent(first: String?, second: String?): Boolean {
        val left = first.orEmpty()
        val right = second.orEmpty()
        if (left.isBlank() || right.isBlank()) return left.isBlank() && right.isBlank()
        return normalize(left) == normalize(right)
    }

    fun match(hint: String, node: UiNodeSnapshot): TargetHintResult =
        if (hint.isBlank()) TargetHintResult.NO_MATCH else {
            val fields = listOf(node.text, node.description, node.viewId, node.className)
            if (score(hint, fields) > 0) TargetHintResult.MATCH else TargetHintResult.NO_MATCH
        }

    fun match(hint: String, nodes: List<UiNodeSnapshot>): TargetHintResult {
        val matches = nodes.count { match(hint, it) == TargetHintResult.MATCH }
        return when {
            matches == 1 -> TargetHintResult.MATCH
            matches > 1 -> TargetHintResult.AMBIGUOUS
            else -> TargetHintResult.NO_MATCH
        }
    }

    private fun score(hint: String, fields: List<String>): Int {
        val normalizedFields = fields.map(::normalize).filter(String::isNotBlank)
        val combined = normalizedFields.joinToString(" ")
        val englishTokens = Regex("[a-z0-9]+").findAll(hint.lowercase(Locale.ROOT)).map { it.value }.toList()
        val chineseSegments = Regex("[\\p{IsHan}]{2,}").findAll(hint).map { it.value }.toList()
        var score = 0
        englishTokens.forEach { token ->
            val aliases = genericAliases[token].orEmpty() + token
            if (aliases.any { alias -> combined.contains(alias) || normalizedFields.any { field -> alias.contains(field) && field.length >= 2 } }) score++
        }
        chineseSegments.forEach { segment ->
            if (combined.contains(normalize(segment)) || normalizedFields.any { field -> field.contains(normalize(segment)) }) score += 2
        }
        if (englishTokens.isNotEmpty() && score * 2 >= englishTokens.size) return score
        return if (chineseSegments.isNotEmpty() && score > 0) score else 0
    }

    private fun normalize(value: String): String = value.trim().lowercase(Locale.ROOT)
        .replace(Regex("[\\p{Punct}\\p{Z}\\s]+"), "")

    private val genericAliases = mapOf(
        "toggle" to setOf("switch", "checkbox", "radiobutton"),
        "switch" to setOf("toggle", "checkbox", "radiobutton"),
        "button" to setOf("action", "control"),
        "field" to setOf("input", "edittext", "textfield"),
    )
}
