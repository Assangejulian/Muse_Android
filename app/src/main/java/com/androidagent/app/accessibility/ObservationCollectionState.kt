package com.androidagent.app.accessibility

/** Mutable completeness signal shared by the production collector and JVM tests. */
internal class ObservationCollectionState(
    private val maxNodes: Int,
    private val maxDepth: Int,
) {
    var isComplete: Boolean = true
        private set

    var nodeLimitReached: Boolean = false
        private set

    var depthLimitReached: Boolean = false
        private set

    var missingApplicationRoots: Int = 0
        private set

    var unresolvedChildren: Int = 0
        private set

    fun shouldStopAtNodeCount(collectedNodes: Int): Boolean {
        if (collectedNodes < maxNodes) return false
        nodeLimitReached = true
        isComplete = false
        return true
    }

    fun recordNodeCount(collectedNodes: Int) {
        if (collectedNodes >= maxNodes) {
            nodeLimitReached = true
            isComplete = false
        }
    }

    fun shouldStopAtDepth(depth: Int): Boolean {
        if (depth <= maxDepth) return false
        depthLimitReached = true
        isComplete = false
        return true
    }

    fun recordMissingApplicationRoot() {
        missingApplicationRoots += 1
        isComplete = false
    }

    fun recordMissingChild() {
        // AccessibilityNodeInfo.getChild() is explicitly nullable. A virtual,
        // recycled, or concurrently changing child therefore does not prove
        // that every other fact in the snapshot is unusable. Keep a quality
        // counter for diagnostics without turning the whole screen UNKNOWN.
        unresolvedChildren += 1
    }

    fun issueSummary(): String = buildList {
        if (nodeLimitReached) add("node_limit")
        if (depthLimitReached) add("depth_limit")
        if (missingApplicationRoots > 0) add("missing_roots=$missingApplicationRoots")
        if (unresolvedChildren > 0) add("unresolved_children=$unresolvedChildren")
    }.joinToString(",").ifBlank { "none" }
}
