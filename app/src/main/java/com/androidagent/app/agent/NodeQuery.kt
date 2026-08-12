package com.androidagent.app.agent

/**
 * App-agnostic tree query. Matching is identity/state based (substring + flags),
 * never a task vocabulary.
 */
data class NodeQuery(
    val text: String? = null,
    val description: String? = null,
    val viewId: String? = null,
    val className: String? = null,
    val clickable: Boolean? = null,
    val checked: Boolean? = null,
    val selected: Boolean? = null,
    val scrollable: Boolean? = null,
    val enabled: Boolean? = null,
    val visible: Boolean? = null,
    val limit: Int = DEFAULT_LIMIT,
) {
    fun hasConstraint(): Boolean =
        text != null || description != null || viewId != null || className != null ||
            clickable != null || checked != null || selected != null || scrollable != null

    fun signature(): String = listOf(
        text.orEmpty(),
        description.orEmpty(),
        viewId.orEmpty(),
        className.orEmpty(),
        clickable?.toString().orEmpty(),
        checked?.toString().orEmpty(),
        selected?.toString().orEmpty(),
        scrollable?.toString().orEmpty(),
        enabled?.toString().orEmpty(),
        visible?.toString().orEmpty(),
        limit.toString(),
    ).joinToString("|")

    companion object {
        const val DEFAULT_LIMIT = 8
        const val MAX_LIMIT = 16
    }
}

data class QueryReport(
    val ok: Boolean,
    val matches: List<UiNodeSnapshot>,
    val summary: String,
    val swipes: Int = 0,
    val alreadyPresent: Boolean = false,
)

object NodeQueryMatcher {
    fun find(observation: Observation, query: NodeQuery): List<UiNodeSnapshot> {
        if (!query.hasConstraint()) return emptyList()
        return observation.nodes.asSequence()
            .filter { node -> !node.password && !node.isInputMethod && matches(node, query) }
            .take(query.limit.coerceIn(1, NodeQuery.MAX_LIMIT))
            .toList()
    }

    fun matches(node: UiNodeSnapshot, query: NodeQuery): Boolean {
        if (query.text != null && !containsIgnoreCase(node.text, query.text)) return false
        if (query.description != null && !containsIgnoreCase(node.description, query.description)) return false
        if (query.viewId != null && !containsIgnoreCase(node.viewId, query.viewId)) return false
        if (query.className != null && !containsIgnoreCase(node.className, query.className)) return false
        if (query.clickable != null && node.clickable != query.clickable) return false
        if (query.checked != null && node.checked != query.checked) return false
        if (query.selected != null && node.selected != query.selected) return false
        if (query.scrollable != null && node.scrollable != query.scrollable) return false
        if (query.enabled != null && node.enabled != query.enabled) return false
        if (query.visible != null && node.visible != query.visible) return false
        return true
    }

    fun format(nodes: List<UiNodeSnapshot>): String {
        if (nodes.isEmpty()) return "matches=0"
        return buildString {
            append("matches=").append(nodes.size)
            nodes.forEach { node ->
                append('\n')
                append('#').append(node.id).append(' ').append(node.className)
                if (node.text.isNotBlank()) append(" text=").append(node.text.take(80))
                if (node.description.isNotBlank()) append(" description=").append(node.description.take(80))
                append(" clickable=").append(node.clickable)
                if (!node.visible) append(" occluded=true")
                append(" bounds=").append(node.bounds)
                if (node.viewId.isNotBlank()) append(" viewId=").append(node.viewId)
                node.checked?.let { append(" checked=").append(it) }
                if (node.selected) append(" selected=true")
                if (node.scrollable) append(" scrollable=true")
                append(" enabled=").append(node.enabled)
            }
        }
    }

    fun execute(action: AgentAction, observation: Observation): QueryReport = when (action) {
        is AgentAction.FindNodes -> {
            val matches = find(observation, action.query)
            QueryReport(
                ok = matches.isNotEmpty(),
                matches = matches,
                summary = format(matches).ifBlank { "matches=0" },
            )
        }
        is AgentAction.ReadNode -> {
            val node = NodeSelector.resolve(observation, action.nodeId, action.selector)
            if (node == null) {
                QueryReport(false, emptyList(), "node not in the current observation")
            } else {
                QueryReport(true, listOf(node), format(listOf(node)))
            }
        }
        else -> QueryReport(false, emptyList(), "not a query action")
    }

    private fun containsIgnoreCase(value: String, needle: String): Boolean {
        if (needle.isBlank()) return false
        return value.contains(needle, ignoreCase = true)
    }
}

object InPlaceProgress {
    fun changed(before: Observation, after: Observation, target: UiNodeSnapshot?): Boolean {
        if (target == null) return false
        val live = ObservationDispatchPolicy.relocate(before, after, target.id) ?: return false
        return live.checked != target.checked || live.selected != target.selected
    }
}

object LocalAgentLoops {
    suspend fun scrollUntil(
        query: NodeQuery,
        direction: String,
        maxSwipes: Int,
        observe: suspend () -> Observation,
        swipe: suspend (String) -> Boolean,
        pauseMillis: Long = 300L,
    ): QueryReport {
        var current = observe()
        val first = NodeQueryMatcher.find(current, query)
        if (first.isNotEmpty()) {
            return QueryReport(
                ok = true,
                matches = first,
                summary = "already on screen\n${NodeQueryMatcher.format(first)}",
                alreadyPresent = true,
            )
        }
        val budget = maxSwipes.coerceIn(1, 12)
        repeat(budget) { index ->
            if (!swipe(direction)) {
                return QueryReport(
                    ok = false,
                    matches = emptyList(),
                    summary = "swipe failed before a match (swipes=${index})",
                    swipes = index,
                )
            }
            kotlinx.coroutines.delay(pauseMillis)
            current = observe()
            val hits = NodeQueryMatcher.find(current, query)
            if (hits.isNotEmpty()) {
                return QueryReport(
                    ok = true,
                    matches = hits,
                    summary = "found after ${index + 1} swipe(s)\n${NodeQueryMatcher.format(hits)}",
                    swipes = index + 1,
                )
            }
        }
        return QueryReport(
            ok = false,
            matches = emptyList(),
            summary = "no match after $budget swipe(s)",
            swipes = budget,
        )
    }

    suspend fun waitUntil(
        query: NodeQuery,
        timeoutMillis: Long,
        observe: suspend () -> Observation,
        pollMillis: Long = 120L,
    ): QueryReport {
        val result = WaitEngine.waitForQuery(query, timeoutMillis, pollMillis, observe)
        return when (result) {
            is WaitResult.Satisfied -> {
                val hits = NodeQueryMatcher.find(result.value, query)
                QueryReport(true, hits, "query satisfied in ${result.elapsedMillis}ms\n${NodeQueryMatcher.format(hits)}")
            }
            is WaitResult.TimedOut -> QueryReport(
                false,
                emptyList(),
                "query not observed within ${result.elapsedMillis}ms",
            )
        }
    }
}
