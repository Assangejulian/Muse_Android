package com.androidagent.app.agent

/** Two-line Chinese overlay copy so a user can see why the Actor chose a step. */
internal object ActorOverlayThought {
    const val MAX_LINE_CHARS = 28

    fun decision(modelThought: String, actionLabel: String, observation: Observation): List<String> {
        val thoughtLines = splitThought(modelThought)
        if (thoughtLines.isNotEmpty()) {
            val second = thoughtLines.getOrNull(1)?.takeIf { it.isNotBlank() } ?: "打算：$actionLabel"
            return listOf(clip(thoughtLines[0]), clip(second))
        }
        val hint = screenHint(observation)
        return listOf(
            clip(if (hint.isBlank()) "正在看当前页面" else "看见：$hint"),
            clip("打算：$actionLabel"),
        )
    }

    fun result(actionLabel: String, reason: String, progressed: Boolean): List<String> = listOf(
        clip("动作：$actionLabel"),
        clip(resultLine(reason, progressed)),
    )

    internal fun splitThought(raw: String): List<String> {
        val cleaned = raw.replace(Regex("\\s+"), " ").trim()
        if (cleaned.isBlank()) return emptyList()
        val sentences = cleaned.split(Regex("[。！？；!?;\\n]+")).map { it.trim() }.filter { it.isNotBlank() }
        if (sentences.size >= 2) return listOf(clip(sentences[0]), clip(sentences.drop(1).joinToString("")))
        if (cleaned.length <= MAX_LINE_CHARS) return listOf(cleaned)
        return listOf(clip(cleaned.take(MAX_LINE_CHARS)), clip(cleaned.drop(MAX_LINE_CHARS)))
    }

    internal fun screenHint(observation: Observation): String {
        val labels = observation.nodes.asSequence()
            .filter { it.visible && !it.password && !it.isInputMethod }
            .map { it.text.ifBlank { it.description }.trim() }
            .filter { it.isNotBlank() && it.length in 1..16 }
            .distinct()
            .take(3)
            .joinToString(" / ")
        val pkg = observation.packageName.substringAfterLast('.').take(16)
        return listOf(pkg, labels).filter { it.isNotBlank() }.joinToString(" · ")
    }

    internal fun resultLine(reason: String, progressed: Boolean): String {
        val value = reason.trim()
        val lower = value.lowercase()
        return when {
            lower.contains("already followed") -> "结果：这个入口刚走过，换一条路"
            lower.contains("package changed") || lower.contains("foreground package") -> "结果：前台应用变了，重新看页"
            lower.contains("missing") || lower.contains("not in the current") -> "结果：没找到要点的控件"
            lower.contains("ambiguous") -> "结果：同名控件太多，没法点准"
            lower.contains("sensitive") || lower.contains("safety") -> "结果：安全策略拦住了"
            lower.contains("finish_rejected") || lower.contains("not yet") -> "结果：还不能收工，继续做"
            lower.contains("stale") -> "结果：页面已切换，重新规划"
            progressed -> "结果：页面有变化，继续"
            value.any { it in '\u4e00'..'\u9fff' } -> "结果：${clip(value, 22)}"
            else -> "结果：这一步没有推进"
        }
    }

    private fun clip(value: String, max: Int = MAX_LINE_CHARS): String = value.trim().take(max)
}
