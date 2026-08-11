package com.androidagent.app.terminal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalEnvironmentTest {
    @Test
    fun validatesExecutableAndAbsoluteEnvironmentPaths() {
        assertTrue(isSafeExecutable("/data/local/tmp/muse/bin/python3"))
        assertFalse(isSafeExecutable("python3; reboot"))
        assertTrue(isSafePath("/sdcard/Muse Workspace"))
        assertFalse(isSafePath("../../data"))
    }
}
