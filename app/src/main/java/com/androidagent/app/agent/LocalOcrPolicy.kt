package com.androidagent.app.agent

/**
 * Local OCR is a privacy-preserving fallback when the accessibility tree is
 * structurally present but semantically empty. Node count alone must not skip
 * it — Chinese custom-view apps often expose 8+ blank clickable frames.
 */
internal object LocalOcrPolicy {
    const val MIN_ACCESSIBLE_TEXT_CHARS = 24
    const val MIN_BLANK_ACTIONABLE_FOR_OCR = 3

    fun shouldEnrich(observation: Observation): Boolean {
        val visible = observation.nodes.filter { it.visible && !it.password && !it.isInputMethod }
        val accessibleTextChars = visible.sumOf { it.text.length + it.description.length }
        val blankActionable = visible.count { node ->
            (node.clickable || node.editable) && node.text.isBlank() && node.description.isBlank()
        }
        if (blankActionable >= MIN_BLANK_ACTIONABLE_FOR_OCR) return true
        return accessibleTextChars < MIN_ACCESSIBLE_TEXT_CHARS
    }
}
