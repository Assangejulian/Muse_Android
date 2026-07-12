package com.androidagent.app.update

import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubUpdaterTest {
    @Test
    fun comparesSemanticVersions() {
        assertTrue(compareVersionNames("0.3.0", "0.2.0") > 0)
    }
}
