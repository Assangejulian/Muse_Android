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

    @Test
    fun streamKeepsPartialLastLineAndDropsFinishedToolLogs() {
        val text = ActorOverlayThought.stream(
            "先看评论区\n[find_nodes(评论)]\n接下来点第一条下面的",
        )
        assertEquals("先看评论区\n接下来点第一条下面的", text)
        assertTrue(ActorOverlayThought.stream("").isEmpty())
    }

    @Test
    fun streamCapsOnlyTheTailSoThePanelCanScroll() {
        val longThought = buildString {
            repeat(400) { appendLine("这是第${it}段思考，模型还在继续往下写。") }
        }
        val streamed = ActorOverlayThought.stream(longThought)
        assertTrue(streamed.length <= ActorOverlayThought.MAX_STREAM_CHARS)
        assertTrue(streamed.contains("这是第399段思考"))
        assertFalse(streamed.contains("这是第0段思考"))
    }
}
