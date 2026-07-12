package com.androidagent.app.accessibility

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ObservationPolicyTest {
    @Test
    fun excludesAgentOverlayNodes() {
        assertFalse(ObservationPolicy.shouldIncludePackage("com.androidagent.app"))
    }

    @Test
    fun keepsTargetAndUnlabeledNodes() {
        assertTrue(ObservationPolicy.shouldIncludePackage("com.ss.android.ugc.aweme.lite"))
        assertTrue(ObservationPolicy.shouldIncludePackage(null))
    }
}
