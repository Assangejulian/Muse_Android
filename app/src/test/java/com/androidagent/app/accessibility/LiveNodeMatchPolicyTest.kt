package com.androidagent.app.accessibility

import com.androidagent.app.agent.UiNodeSnapshot
import org.junit.Assert.assertEquals
import org.junit.Test

class LiveNodeMatchPolicyTest {
    @Test
    fun picksTheClosestBoundsWhenSeveralNodesShareIdentity() {
        val snapshot = UiNodeSnapshot(
            1, "", "icon", "ImageView", true, false, "600,800,680,880",
        )
        val picked = LiveNodeMatchPolicy.choose(
            candidates = listOf("10,10,40,40", "610,810,670,870", "0,0,20,20"),
            snapshot = snapshot,
            boundsOf = { it },
        )
        assertEquals("610,810,670,870", picked)
    }
}
