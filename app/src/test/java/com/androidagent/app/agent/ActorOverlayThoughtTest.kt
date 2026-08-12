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
        assertEquals(2, lines.size)
        assertTrue(lines[0].contains("热搜"))
        assertTrue(lines[1].isNotBlank())
        lines.forEach { assertTrue(it.length <= ActorOverlayThought.MAX_LINE_CHARS) }
    }

    @Test
    fun fallsBackToScreenHintWhenModelHasNoThought() {
        val screen = Observation(
            "tv.danmaku.bili",
            listOf(UiNodeSnapshot(1, "热搜", "", "Button", true, false, "0,0,80,40")),
        )
        val lines = ActorOverlayThought.decision("", "click_text(热搜)", screen)
        assertEquals("看见：bili · 热搜", lines[0])
        assertEquals("打算：click_text(热搜)", lines[1])
    }

    @Test
    fun resultLineExplainsABlockedDetour() {
        val lines = ActorOverlayThought.result(
            "click_node(#12, 9.1武侠)",
            "already followed 9.1武侠 and left that page",
            progressed = false,
        )
        assertEquals("动作：click_node(#12, 9.1武侠)", lines[0])
        assertEquals("结果：这个入口刚走过，换一条路", lines[1])
    }
}
