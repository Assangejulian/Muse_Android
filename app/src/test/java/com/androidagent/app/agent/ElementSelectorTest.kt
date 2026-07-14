package com.androidagent.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    private fun node(id: Int, text: String, bounds: String) =
        UiNodeSnapshot(id, text, "", "Button", true, false, bounds, packageName = "example.app")
}
