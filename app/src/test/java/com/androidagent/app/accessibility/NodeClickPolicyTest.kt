package com.androidagent.app.accessibility

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NodeClickPolicyTest {
    @Test
    fun acceptsVisibleContentBounds() {
        assertTrue(NodeClickPolicy.isSafeBounds(900, 500, 1050, 650, 1200, 1920))
    }

    @Test
    fun rejectsSystemBarAndInvalidBounds() {
        assertFalse(NodeClickPolicy.isSafeBounds(0, 0, 100, 20, 1200, 1920))
        assertFalse(NodeClickPolicy.isSafeBounds(100, 200, 90, 240, 1200, 1920))
    }
}
