package com.androidagent.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ActorOverlayThoughtTest {
    @Test
    fun splitsChineseThoughtIntoTwoOverlayLines() {
        val lines = ActorOverlayThought.decision(
            modelThought = "现在在B站热搜页。第一条是影之刃预告，应该点进去再找评论。",
            actionLabel = "click_text(影之刃)",
            observation = Observation("tv.danmaku.bili", emptyList()),
        )
        assertTrue(lines.size >= 2)
        assertTrue(lines[0].contains("热搜"))
        assertTrue(lines.any { it.contains("影之刃") })
        assertTrue(lines.any { it.contains("click_text") })
        lines.forEach { assertTrue(it.length <= ActorOverlayThought.MAX_LINE_CHARS) }
    }

    @Test
    fun fallsBackToScreenHintWhenModelHasNoThought() {
        val screen = Observation(
            "tv.danmaku.bili",
            listOf(UiNodeSnapshot(1, "热搜", "", "Button", true, false, "0,0,80,40")),
        )
        val lines = ActorOverlayThought.decision("", "click_text(热搜)", screen)
        assertTrue(lines.any { it.contains("click_text(热搜)") })
    }

    @Test
    fun resultKeepsTheRawReason() {
        val lines = ActorOverlayThought.result(
            "click_node(#12, 9.1武侠)",
            "already followed 9.1武侠 and left that page",
            progressed = false,
        )
        assertEquals("→ click_node(#12, 9.1武侠)", lines[0])
        assertTrue(lines[1].contains("already followed 9.1武侠"))
    }

    @Test
    fun keepsModelNewlinesInsteadOfRewritingThem() {
        val lines = ActorOverlayThought.decision(
            modelThought = "first line\nsecond line",
            actionLabel = "find_nodes",
            observation = Observation("app.example", emptyList()),
        )
        assertEquals("first line", lines[0])
        assertEquals("second line", lines[1])
        assertEquals("[find_nodes]", lines[2])
    }
}
