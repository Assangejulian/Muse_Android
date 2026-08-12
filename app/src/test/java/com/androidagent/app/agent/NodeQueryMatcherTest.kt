package com.androidagent.app.agent

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NodeQueryMatcherTest {
    private val comment = UiNodeSnapshot(
        id = 4,
        text = "第一条评论",
        description = "",
        className = "TextView",
        clickable = false,
        editable = false,
        bounds = "20,800,400,860",
        packageName = "app.example",
        visible = true,
    )
    private val like = UiNodeSnapshot(
        id = 5,
        text = "12",
        description = "like",
        className = "ImageView",
        clickable = true,
        editable = false,
        bounds = "620,810,700,860",
        checked = false,
        packageName = "app.example",
        visible = false,
    )
    private val screen = Observation("app.example", listOf(comment, like))

    @Test
    fun substringAndStateFiltersAreConjunctiveAndIncludeOccludedNodes() {
        val byDesc = NodeQueryMatcher.find(screen, NodeQuery(description = "LIKE", clickable = true))
        assertEquals(listOf(5), byDesc.map { it.id })
        val tooStrict = NodeQueryMatcher.find(screen, NodeQuery(description = "like", checked = true))
        assertTrue(tooStrict.isEmpty())
        val empty = NodeQueryMatcher.find(screen, NodeQuery())
        assertTrue(empty.isEmpty())
    }

    @Test
    fun readNodeReportsCheckedAndOccludedFlags() {
        val report = NodeQueryMatcher.execute(AgentAction.ReadNode(5), screen)
        assertTrue(report.ok)
        assertTrue(report.summary.contains("checked=false"))
        assertTrue(report.summary.contains("occluded=true"))
    }

    @Test
    fun scrollUntilStopsWithoutSwipingWhenAlreadyPresent() = runBlocking {
        var swipes = 0
        val report = LocalAgentLoops.scrollUntil(
            query = NodeQuery(description = "like"),
            direction = "up",
            maxSwipes = 4,
            observe = { screen },
            swipe = { swipes += 1; true },
        )
        assertTrue(report.ok)
        assertTrue(report.alreadyPresent)
        assertEquals(0, swipes)
        assertEquals(0, report.swipes)
    }

    @Test
    fun scrollUntilSwipesUntilTheQueryAppears() = runBlocking {
        var swipes = 0
        val empty = Observation("app.example", listOf(comment))
        val report = LocalAgentLoops.scrollUntil(
            query = NodeQuery(description = "like"),
            direction = "up",
            maxSwipes = 4,
            observe = { if (swipes == 0) empty else screen },
            swipe = { swipes += 1; true },
            pauseMillis = 0,
        )
        assertTrue(report.ok)
        assertEquals(1, report.swipes)
        assertEquals(5, report.matches.single().id)
    }
}
