package com.androidagent.app.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class GitHubUpdaterTest {
    @Test
    fun comparesSemanticVersions() {
        assertTrue(compareVersionNames("0.3.0", "0.2.0") > 0)
    }

    @Test
    fun parsesNewerReleaseWithPinnedApkDigest() {
        val payload = """
            {
              "tag_name": "v0.14.3",
              "body": "Restore updates",
              "assets": [{
                "name": "Muse-0.14.3-debug.apk",
                "browser_download_url": "https://github.com/Assangejulian/Muse_Android/releases/download/v0.14.3/Muse-0.14.3-debug.apk",
                "digest": "sha256:${"a".repeat(64)}",
                "size": 123456
              }]
            }
        """.trimIndent()

        val update = parseLatestRelease(payload, "0.14.2")

        assertEquals("0.14.3", update?.version)
        assertEquals("a".repeat(64), update?.sha256)
        assertEquals(123456L, update?.sizeBytes)
        assertNull(parseLatestRelease(payload, "0.14.3"))
    }

    @Test
    fun rejectsReleaseApkWithoutGitHubDigest() {
        val payload = """
            {
              "tag_name": "v0.14.3",
              "assets": [{
                "name": "Muse.apk",
                "browser_download_url": "https://github.com/example/Muse.apk"
              }]
            }
        """.trimIndent()

        assertThrows(IllegalArgumentException::class.java) {
            parseLatestRelease(payload, "0.14.2")
        }
    }
}
