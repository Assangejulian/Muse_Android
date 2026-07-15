package com.androidagent.app.accessibility

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ObservationCollectionStateTest {
    @Test
    fun reachingNodeLimitMarksCollectionIncomplete() {
        val state = ObservationCollectionState(maxNodes = 250, maxDepth = 30)
        state.recordNodeCount(250)
        assertFalse(state.isComplete)
        assertTrue(state.shouldStopAtNodeCount(250))
    }

    @Test
    fun exceedingDepthMarksCollectionIncomplete() {
        val state = ObservationCollectionState(maxNodes = 250, maxDepth = 30)
        assertTrue(state.shouldStopAtDepth(31))
        assertFalse(state.isComplete)
    }

    @Test
    fun normalTreeRemainsComplete() {
        val state = ObservationCollectionState(maxNodes = 250, maxDepth = 30)
        state.recordNodeCount(249)
        assertFalse(state.shouldStopAtNodeCount(249))
        assertFalse(state.shouldStopAtDepth(30))
        assertTrue(state.isComplete)
    }

    @Test
    fun missingExpectedRootOrChildMarksCollectionIncomplete() {
        val missingRoot = ObservationCollectionState(maxNodes = 250, maxDepth = 30)
        missingRoot.recordMissingApplicationRoot()
        assertFalse(missingRoot.isComplete)

        val missingChild = ObservationCollectionState(maxNodes = 250, maxDepth = 30)
        missingChild.recordMissingChild()
        assertFalse(missingChild.isComplete)
    }
}
