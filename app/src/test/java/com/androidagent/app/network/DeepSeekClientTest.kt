package com.androidagent.app.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepSeekClientTest {
    @Test
    fun extractsJsonFromMarkdownFence() {
        assertEquals(
            "{\"mode\":\"chat\",\"reply\":\"hello\"}",
            JsonResponse.extractObject("```json\n{\"mode\":\"chat\",\"reply\":\"hello\"}\n```"),
        )
    }

    @Test
    fun retainsRecentHistoryWithinOneMillionTokenBudget() {
        val oversized = "a".repeat(ContextWindow.MAX_CONTEXT_TOKENS * 3 + 100)
        val selected = ContextWindow.select(listOf("user" to "old", "assistant" to oversized))

        assertEquals(1, selected.size)
        assertEquals(ContextWindow.MAX_CONTEXT_TOKENS * 3, selected.single().second.length)
        assertTrue(selected.single().second.all { it == 'a' })
    }
}
