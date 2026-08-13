package com.androidagent.app.agent

/**
 * Overlay COT is the model's reasoning only — the same kind of thinking
 * stream a chat UI shows before a tool runs. Tool names, REPEAT lines, and
 * dispatch English are not thought.
 */
internal object ActorOverlayThought {
    const val MAX_LINE_CHARS = 240
    const val MAX_STORED_LINES = 16

    fun cot(modelThought: String): List<String> =
        splitThought(modelThought).filterNot(::isToolLog).map(::clip)

    fun decision(modelThought: String, @Suppress("UNUSED_PARAMETER") actionLabel: String, @Suppress("UNUSED_PARAMETER") observation: Observation): List<String> =
        cot(modelThought)

    fun result(
        @Suppress("UNUSED_PARAMETER") actionLabel: String,
        @Suppress("UNUSED_PARAMETER") reason: String,
        @Suppress("UNUSED_PARAMETER") progressed: Boolean,
    ): List<String> = emptyList()

    fun merge(existing: List<String>, incoming: List<String>): List<String> {
        val next = existing.toMutableList()
        incoming.map { it.trim() }.filter { it.isNotBlank() }.filterNot(::isToolLog).forEach { line ->
            if (next.lastOrNull() != line) next += line
        }
        return next.takeLast(MAX_STORED_LINES)
    }

    internal fun splitThought(raw: String): List<String> {
        val cleaned = raw.replace("\r\n", "\n").replace('\r', '\n').trim()
        if (cleaned.isBlank()) return emptyList()
        val lines = cleaned.split('\n').map { it.trim() }.filter { it.isNotBlank() }
        if (lines.size > 1) return lines
        return listOf(cleaned)
    }

    internal fun isToolLog(line: String): Boolean {
        val value = line.trim()
        if (value.isEmpty()) return true
        val lower = value.lowercase()
        return value.startsWith("→ ") ||
            value.startsWith("[") && value.endsWith("]") ||
            lower.startsWith("repeat:") ||
            lower.startsWith("query_loop:") ||
            lower.startsWith("user_skip:") ||
            lower.contains("no model thought") ||
            lower.contains("previous dispatch") ||
            lower.contains("just ran") ||
            lower.startsWith("find_nodes") ||
            lower.startsWith("click_node") ||
            lower.startsWith("click_text") ||
            lower.startsWith("scroll_until") ||
            lower.startsWith("read_node")
    }

    private fun clip(value: String, max: Int = MAX_LINE_CHARS): String = value.trim().take(max)
}
