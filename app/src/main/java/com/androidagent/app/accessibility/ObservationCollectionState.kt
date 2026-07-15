package com.androidagent.app.accessibility

/** Mutable completeness signal shared by the production collector and JVM tests. */
internal class ObservationCollectionState(
    private val maxNodes: Int,
    private val maxDepth: Int,
) {
    var isComplete: Boolean = true
        private set

    fun shouldStopAtNodeCount(collectedNodes: Int): Boolean {
        if (collectedNodes < maxNodes) return false
        isComplete = false
        return true
    }

    fun recordNodeCount(collectedNodes: Int) {
        if (collectedNodes >= maxNodes) isComplete = false
    }

    fun shouldStopAtDepth(depth: Int): Boolean {
        if (depth <= maxDepth) return false
        isComplete = false
        return true
    }

    fun recordMissingApplicationRoot() {
        isComplete = false
    }

    fun recordMissingChild() {
        isComplete = false
    }
}
