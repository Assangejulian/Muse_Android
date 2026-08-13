package com.androidagent.app.agent

/**
 * Overlay / chat COT. Pass the model text through. Do not rewrite it into a
 * scripted 看见/打算/结果 template — that hid the real chain of thought.
 */
internal object ActorOverlayThought {
    const val MAX_LINE_CHARS = 240
    const val MAX_STORED_LINES = 16

    fun decision(modelThought: String, actionLabel: String, observation: Observation): List<String> {
        val thoughtLines = splitThought(modelThought)
        val action = actionLabel.trim()
        return when {
            thoughtLines.isNotEmpty() && action.isNotBlank() ->
                (thoughtLines + "[$action]").distinct().map(::clip)
            thoughtLines.isNotEmpty() -> thoughtLines.map(::clip)
            action.isNotBlank() -> listOf("[no model thought]", "[$action]")
            else -> {
                val hint = screenHint(observation)
                listOf(if (hint.isBlank()) "[no model thought]" else hint)
            }
        }
    }

    fun result(actionLabel: String, reason: String, @Suppress("UNUSED_PARAMETER") progressed: Boolean): List<String> {
        val action = actionLabel.trim()
        val raw = reason.trim()
        return buildList {
            if (action.isNotBlank()) add("→ $action")
            if (raw.isNotBlank()) addAll(splitThought(raw))
        }.map(::clip)
    }

    fun merge(existing: List<String>, incoming: List<String>): List<String> {
        val next = existing.toMutableList()
        incoming.map { it.trim() }.filter { it.isNotBlank() }.forEach { line ->
            if (next.lastOrNull() != line) next += line
        }
        return next.takeLast(MAX_STORED_LINES)
    }

    internal fun splitThought(raw: String): List<String> {
        val cleaned = raw.replace("\r\n", "\n").replace('\r', '\n').trim()
        if (cleaned.isBlank()) return emptyList()
        val lines = cleaned.split('\n').map { it.trim() }.filter { it.isNotBlank() }
        if (lines.size > 1) return lines.map(::clip)
        return listOf(clip(cleaned))
    }

    internal fun screenHint(observation: Observation): String {
        val labels = observation.nodes.asSequence()
            .filter { !it.password && !it.isInputMethod }
            .map { it.text.ifBlank { it.description }.trim() }
            .filter { it.isNotBlank() && it.length in 1..16 }
            .distinct()
            .take(5)
            .joinToString(" / ")
        val pkg = observation.packageName.substringAfterLast('.').take(16)
        return listOf(pkg, labels).filter { it.isNotBlank() }.joinToString(" · ")
    }

    private fun clip(value: String, max: Int = MAX_LINE_CHARS): String = value.trim().take(max)
}
