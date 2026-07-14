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
    private data class HintScore(val semantic: Int, val structure: Int) {
        val total: Int get() = semantic + structure
    }

    fun semanticallyEquivalent(first: String?, second: String?): Boolean {
        val left = first.orEmpty()
        val right = second.orEmpty()
        if (left.isBlank() || right.isBlank()) return left.isBlank() && right.isBlank()
        return normalize(left) == normalize(right)
    }

    fun match(hint: String, node: UiNodeSnapshot): TargetHintResult =
        if (hint.isBlank()) TargetHintResult.NO_MATCH else {
            if (score(hint, node).semantic > 0) TargetHintResult.MATCH else TargetHintResult.NO_MATCH
        }

    fun match(hint: String, nodes: List<UiNodeSnapshot>): TargetHintResult {
        if (hint.isBlank()) return TargetHintResult.NO_MATCH
        val scored = nodes.map { it to score(hint, it) }.filter { it.second.semantic > 0 }
        if (scored.isEmpty()) return TargetHintResult.NO_MATCH
        val best = scored.maxOf { it.second.total }
        val matches = scored.count { it.second.total == best }
        return when {
            matches == 1 -> TargetHintResult.MATCH
            matches > 1 -> TargetHintResult.AMBIGUOUS
            else -> TargetHintResult.NO_MATCH
        }
    }

    private fun score(hint: String, node: UiNodeSnapshot): HintScore {
        val fields = listOf(node.text, node.description, node.viewId)
            .filter(String::isNotBlank)
            .map(::normalize)
        val fieldTokens = fields.flatMap { Regex("[a-z0-9]+|[\\p{IsHan}]{1,}").findAll(it).map { match -> match.value }.toList() }
        var semantic = 0
        var structure = 0

        Regex("[a-z0-9]+").findAll(hint.lowercase(Locale.ROOT)).forEach { match ->
            val token = match.value
            val generic = token in genericEnglish
            val fieldMatch = fieldTokens.any { field -> englishTokenMatches(token, field) }
            if (generic) {
                if (classMatches(node.className, token)) structure += 2
            } else if (fieldMatch) {
                semantic += if (fieldTokens.any { it == token }) 100 else 60
            }
        }

        chineseSemanticSegments(hint).forEach { segment ->
            if (fields.any { it.contains(segment) }) semantic += 90
        }

        // A control class can support a semantic match but can never create
        // one by itself. This prevents a generic "switch" hint from selecting
        // the first switch on a page.
        return HintScore(semantic = semantic, structure = structure.coerceAtMost(6))
    }

    private fun normalize(value: String): String = value.trim().lowercase(Locale.ROOT)
        .replace(Regex("[\\p{Punct}\\p{Z}\\s]+"), "")

    private fun englishTokenMatches(token: String, field: String): Boolean {
        if (token.length < 3) return field == token
        if (field == token) return true
        if (field.removeSuffix("s") == token || token.removeSuffix("s") == field) return true
        return field.contains(token) || token.contains(field) && field.length >= 3
    }

    private fun classMatches(className: String, token: String): Boolean {
        val normalized = className.lowercase(Locale.ROOT)
        return genericAliases[token].orEmpty().any { normalized.contains(it) } || normalized.contains(token)
    }

    private fun chineseSemanticSegments(hint: String): Set<String> {
        val genericWords = listOf("设置", "按钮", "开关", "选项", "控件", "页面")
        val runs = Regex("[\\p{IsHan}]+").findAll(hint).map { it.value }
        return runs.flatMap { raw ->
            var cleaned = raw
            genericWords.forEach { word -> cleaned = cleaned.replace(word, "") }
            buildSet {
                for (start in cleaned.indices) {
                    for (length in 2..4) {
                        if (start + length <= cleaned.length) add(cleaned.substring(start, start + length))
                    }
                }
            }
        }.filter(String::isNotBlank).toSet()
    }

    private val genericEnglish = setOf(
        "toggle", "switch", "checkbox", "radiobutton", "button", "control", "field", "input", "edittext", "textfield",
        "settings", "setting", "option", "page",
    )

    private val genericAliases = mapOf(
        "toggle" to setOf("switch", "checkbox", "radiobutton", "toggle"),
        "switch" to setOf("switch", "checkbox", "radiobutton", "toggle"),
        "checkbox" to setOf("switch", "checkbox", "radiobutton", "toggle"),
        "radiobutton" to setOf("switch", "checkbox", "radiobutton", "toggle"),
        "button" to setOf("button", "action", "control"),
        "control" to setOf("button", "control"),
        "field" to setOf("input", "edittext", "textfield"),
        "input" to setOf("input", "edittext", "textfield"),
        "edittext" to setOf("input", "edittext", "textfield"),
        "textfield" to setOf("input", "edittext", "textfield"),
    )
}
