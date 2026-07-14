package com.androidagent.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ElementSelectorTest {
    @Test
    fun oldNumericIdIsNotUsedToSelectAReorderedElement() {
        val old = Observation("example.app", listOf(
            node(1, "Target", "0,0,100,30"),
            node(2, "Other", "0,40,100,70"),
        ))
        val current = Observation("example.app", listOf(
            node(1, "Other", "0,0,100,30"),
            node(2, "Target", "0,40,100,70"),
        ))
        val selector = NodeSelector.from(old.nodes[0])
        assertEquals(2, NodeSelector.resolve(current, old.nodes[0].id, selector)?.id)
    }

    @Test
    fun duplicateTextSelectorReturnsAmbiguous() {
        val observation = Observation("example.app", listOf(node(1, "Same", "0,0,100,30"), node(2, "Same", "0,40,100,70")))
        assertNull(NodeSelector.resolve(observation, null, ElementSelector(text = "Same", className = "Button")))
    }

    @Test
    fun selectorFailureNeverFallsBackToConflictingNodeId() {
        val observation = Observation("example.app", listOf(node(1, "Old", "0,0,100,30")))
        val selector = ElementSelector(text = "Missing", className = "Button")
        assertNull(NodeSelector.resolve(observation, 1, selector))
    }

    @Test
    fun selectorParserRejectsEmptyPackageOnlyClassOnlyAndBadBounds() {
        assertTrue(runCatching { ElementSelectorJson.parse(org.json.JSONObject("{}")) }.isFailure)
        assertTrue(runCatching { ElementSelectorJson.parse(org.json.JSONObject("{\"packageName\":\"example.app\"}")) }.isFailure)
        assertTrue(runCatching { ElementSelectorJson.parse(org.json.JSONObject("{\"className\":\"Button\"}")) }.isFailure)
        assertTrue(runCatching { ElementSelectorJson.parse(org.json.JSONObject("{\"text\":\"x\",\"bounds\":\"bad\"}")) }.isFailure)
        assertTrue(runCatching { ElementSelectorJson.parse(org.json.JSONObject("{\"text\":\"x\",\"treePath\":[]}")) }.isFailure)
    }

    @Test
    fun textOnlyAndDescriptionOnlySelectorsRequireUniqueMatch() {
        val observation = Observation("example.app", listOf(
            node(1, "Unique", "", "Button", "0,0,100,30"),
            node(2, "Other", "Info", "Button", "0,40,100,70"),
        ))
        assertEquals(1, NodeSelector.matchingNodes(observation, ElementSelector(text = "Unique")).single().id)
        assertEquals(2, NodeSelector.matchingNodes(observation, ElementSelector(description = "Info")).single().id)
    }

    @Test
    fun duplicateViewIdCanBeDisambiguatedByTextOrTreePath() {
        val observation = Observation("example.app", listOf(
            node(1, "A", "", "Button", "0,0,100,30").copy(viewId = "example:id/action", treePath = listOf(0)),
            node(2, "B", "", "Button", "0,40,100,70").copy(viewId = "example:id/action", treePath = listOf(1)),
        ))
        assertEquals(2, NodeSelector.resolve(observation, null, ElementSelector(viewIdResourceName = "example:id/action", text = "B"))?.id)
        assertEquals(1, NodeSelector.resolve(observation, null, ElementSelector(viewIdResourceName = "example:id/action", treePath = listOf(0)))?.id)
    }

    @Test
    fun duplicateViewIdUsesTreePathAfterTextAndClassMatch() {
        val observation = Observation("example.app", listOf(
            node(1, "Same", "0,0,100,30").copy(viewId = "example:id/action", treePath = listOf(0)),
            node(2, "Same", "0,40,100,70").copy(viewId = "example:id/action", treePath = listOf(1)),
        ))
        val selector = ElementSelector(
            viewIdResourceName = "example:id/action",
            text = "Same",
            className = "Button",
            treePath = listOf(1),
        )
        assertEquals(2, NodeSelector.resolve(observation, null, selector)?.id)
    }

    @Test
    fun sharedViewIdKeepsDifferentStableIdentitiesSeparate() {
        val before = Observation("example.app", listOf(
            node(1, "A", "0,0,100,30").copy(viewId = "example:id/action", treePath = listOf(0)),
            node(2, "B", "0,40,100,70").copy(viewId = "example:id/action", treePath = listOf(1)),
        ))
        val after = Observation("example.app", listOf(
            node(8, "A changed", "2,2,102,32").copy(viewId = "example:id/action", treePath = listOf(0)),
            node(9, "B", "0,40,100,70").copy(viewId = "example:id/action", treePath = listOf(1)),
        ))
        val identity = BoundElementIdentity.from(before.nodes.first())
        assertEquals(listOf(8), NodeSelector.matchingNodes(after, identity).map { it.id })
    }

    @Test
    fun treePathAndBoundsFallbackSurviveSmallMovementWithoutViewId() {
        val before = node(1, "", "0,0,100,30").copy(
            treePath = listOf(2),
            withinWindowStableKey = "row-2",
            crossWindowStructureKey = "row-2-cross",
        )
        val after = node(7, "changed", "3,3,103,33").copy(
            treePath = listOf(2),
            withinWindowStableKey = "row-2",
            crossWindowStructureKey = "row-2-cross",
        )
        val identity = BoundElementIdentity.from(before)
        assertEquals(7, NodeSelector.matchingNodes(Observation("example.app", listOf(after)), identity).single().id)
    }

    private fun node(id: Int, text: String, bounds: String) = node(id, text, "", "Button", bounds)

    private fun node(id: Int, text: String, description: String, className: String, bounds: String) =
        UiNodeSnapshot(id, text, description, className, true, false, bounds, packageName = "example.app")
}
