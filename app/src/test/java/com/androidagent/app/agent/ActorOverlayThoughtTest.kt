package com.androidagent.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActorOverlayThoughtTest {
    @Test
    fun cotKeepsChineseReasoningAndDropsToolLogs() {
        val lines = ActorOverlayThought.cot(
            "现在在评论区，第一条下面有点赞。\n应该点那个赞。\n[find_nodes(评论)]\n→ click_node(#119)",
        )
        assertEquals(listOf("现在在评论区，第一条下面有点赞。", "应该点那个赞。"), lines)
        assertTrue(lines.none { it.contains("find_nodes") || it.contains("click_node") })
    }

    @Test
    fun emptyOrToolOnlyThoughtProducesNoOverlayLines() {
        assertTrue(ActorOverlayThought.cot("").isEmpty())
        assertTrue(ActorOverlayThought.cot("[no model thought]").isEmpty())
        assertTrue(ActorOverlayThought.cot("REPEAT: find_nodes just ran").isEmpty())
        assertTrue(ActorOverlayThought.result("click_node(#1)", "unknown", false).isEmpty())
    }

    @Test
    fun mergeIgnoresToolLogLines() {
        val merged = ActorOverlayThought.merge(
            listOf("先打开评论区"),
            listOf("→ find_nodes(评论)", "点第一条下面的赞"),
        )
        assertEquals(listOf("先打开评论区", "点第一条下面的赞"), merged)
        assertFalse(merged.any { it.startsWith("→ ") })
    }
}
