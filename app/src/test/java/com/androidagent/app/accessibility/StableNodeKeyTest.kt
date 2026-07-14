package com.androidagent.app.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class StableNodeKeyTest {
    @Test
    fun withinWindowAndCrossWindowKeysHaveDifferentWindowSemantics() {
        val first = AgentAccessibilityService.stableNodeKey("example.app", 3, "example:id/control", "android.widget.Switch", listOf(0, 2))
        val sameWithinWindow = AgentAccessibilityService.stableNodeKey("example.app", 3, "example:id/control", "android.widget.Switch", listOf(0, 2))
        val changedPath = AgentAccessibilityService.stableNodeKey("example.app", 3, "example:id/control", "android.widget.Switch", listOf(0, 3))
        val changedWindow = AgentAccessibilityService.stableNodeKey("example.app", 4, "example:id/control", "android.widget.Switch", listOf(0, 2))
        val crossFirst = AgentAccessibilityService.crossWindowStructureKey("example.app", "example:id/control", "android.widget.Switch", listOf(0, 2))
        val crossRecreated = AgentAccessibilityService.crossWindowStructureKey("example.app", "example:id/control", "android.widget.Switch", listOf(0, 2))

        assertEquals(first, sameWithinWindow)
        assertNotEquals(first, changedPath)
        assertNotEquals(first, changedWindow)
        assertEquals(crossFirst, crossRecreated)
    }
}
