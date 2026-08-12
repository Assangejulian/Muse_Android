package com.androidagent.app.agent

/**
 * App-agnostic escape hatch. If a goal token is still unseen and a near-miss
 * control (热门 vs 热搜) is being clicked, push the Actor toward search/swipe.
 */
internal object UnstickPolicy {
    private val generic = setOf(
        "打开", "帮我", "一下", "一个", "第一", "第一个", "一条", "这个", "那个", "进入",
        "找到", "完成", "进行", "使用", "然后", "之后", "现在", "给我", "打开应用", "应用",
    )

    fun tokens(goal: String): List<String> {
        val quoted = Regex("[\"“”']([^\"“”']{2,16})[\"“”']").findAll(goal).map { it.groupValues[1].trim() }
        val latin = Regex("[A-Za-z][A-Za-z0-9]{1,}").findAll(goal).map { it.value }
        val chunks = goal.split(Regex("[的了在给和与或把到去以及，。,\\s]+"))
        val refined = chunks.flatMap { chunk ->
            var stripped = chunk
            generic.forEach { word -> stripped = stripped.replace(word, " ") }
            stripped.split(Regex("[^\\p{IsHan}A-Za-z0-9]+"))
                .filter { it.length >= 2 }
                .flatMap { word ->
                    if (word.length <= 3) listOf(word)
                    else listOf(word) + word.chunked(2).filter { it.length >= 2 }
                }
        }
        return (quoted + latin + refined)
            .map { it.trim() }
            .filter { it.length >= 2 && it !in generic }
            .distinct()
            .toList()
    }

    fun missingTokens(goal: String, observation: Observation, alreadySeen: Set<String>): List<String> {
        val visible = observation.visibleText()
        return tokens(goal).filter { token ->
            token !in alreadySeen && !visible.contains(token, ignoreCase = true)
        }
    }

    fun seenOn(observation: Observation, goal: String): Set<String> {
        val visible = observation.visibleText()
        return tokens(goal).filter { visible.contains(it, ignoreCase = true) }.toSet()
    }

    fun searchTargets(observation: Observation): List<UiNodeSnapshot> = observation.nodes.filter { node ->
        if (!node.visible || node.password || node.isInputMethod) return@filter false
        node.editable || looksLikeSearch(node.text) || looksLikeSearch(node.description) ||
            looksLikeSearch(node.viewId)
    }

    fun isSearchTarget(node: UiNodeSnapshot?): Boolean =
        node != null && (node.editable || looksLikeSearch(node.text) || looksLikeSearch(node.description) || looksLikeSearch(node.viewId))

    fun isNearMiss(clicked: String, token: String): Boolean {
        val left = clicked.trim()
        val right = token.trim()
        if (left.isBlank() || right.isBlank() || left.equals(right, true)) return false
        if (left.length !in 2..8 || right.length !in 2..8) return false
        return editDistance(left.lowercase(), right.lowercase()) == 1
    }

    fun nearMissToken(clicked: String, missing: List<String>): String? =
        missing.firstOrNull { isNearMiss(clicked, it) }

    fun harnessHint(
        missing: List<String>,
        searchTargets: List<UiNodeSnapshot>,
        stuck: Boolean,
    ): String {
        if (missing.isEmpty() && !stuck && searchTargets.isEmpty()) return ""
        return buildString {
            if (missing.isNotEmpty()) appendLine("goalTokensMissing=${missing.take(6).joinToString(",")}")
            if (searchTargets.isNotEmpty()) {
                appendLine(
                    "searchAvailable=" + searchTargets.take(3).joinToString(",") { node ->
                        "#${node.id}:${node.text.ifBlank { node.description }.ifBlank { "search" }.take(12)}"
                    },
                )
            }
            if (stuck) appendLine("unstick=true")
            if (missing.isNotEmpty() && searchTargets.isNotEmpty()) {
                append("unstickAdvice=needed tokens are not on this screen; use search or swipe, do not click a similar tab")
            } else if (stuck) {
                append("unstickAdvice=choose swipe, search, back, or a never-tried control")
            }
        }.trim()
    }

    private fun looksLikeSearch(value: String): Boolean {
        val text = value.lowercase()
        return text.contains("search") || text.contains("query") || text.contains("搜") || text.contains("查找")
    }

    internal fun editDistance(left: String, right: String): Int {
        if (left == right) return 0
        if (kotlin.math.abs(left.length - right.length) > 1) return 2
        val rows = left.length + 1
        val cols = right.length + 1
        var previous = IntArray(cols) { it }
        var current = IntArray(cols)
        for (i in 1 until rows) {
            current[0] = i
            for (j in 1 until cols) {
                val cost = if (left[i - 1] == right[j - 1]) 0 else 1
                current[j] = minOf(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + cost)
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[right.length]
    }
}
