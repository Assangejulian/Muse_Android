package com.androidagent.app.accessibility

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ObservationNodePolicyTest {
    @Test
    fun collectsOnScreenNodesEvenWhenNotVisibleToUser() {
        assertTrue(
            ObservationNodePolicy.shouldCollect(
                visibleToUser = false,
                onScreen = true,
                clickable = true,
                editable = false,
                scrollable = false,
                checkable = false,
                selected = false,
                hasText = false,
                hasDescription = true,
                hasViewId = false,
            ),
        )
    }

    @Test
    fun dropsOffScreenNodesThatAreNotVisibleToUser() {
        assertFalse(
            ObservationNodePolicy.shouldCollect(
                visibleToUser = false,
                onScreen = false,
                clickable = true,
                editable = false,
                scrollable = false,
                checkable = false,
                selected = false,
                hasText = true,
                hasDescription = false,
                hasViewId = false,
            ),
        )
    }

    @Test
    fun onScreenRequiresAPositiveIntersection() {
        assertTrue(ObservationNodePolicy.isOnScreen(0, 2000, 100, 2200, 1080, 2400))
        assertFalse(ObservationNodePolicy.isOnScreen(0, 2500, 100, 2700, 1080, 2400))
        assertFalse(ObservationNodePolicy.isOnScreen(10, 10, 10, 40, 1080, 2400))
    }
}
