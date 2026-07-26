package com.androidagent.app.privileged

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivilegedCommandBuilderTest {
    @Test
    fun launchAcceptsOnlyPackageNames() {
        assertNotNull(PrivilegedCommandBuilder.launchPackage("tv.danmaku.bili"))
        assertNull(PrivilegedCommandBuilder.launchPackage("tv.danmaku.bili; reboot"))
        assertNull(PrivilegedCommandBuilder.launchPackage(""))
    }

    @Test
    fun inputCommandsValidateNumericBounds() {
        assertEquals("input tap 100 200", PrivilegedCommandBuilder.tap(100, 200))
        assertNull(PrivilegedCommandBuilder.tap(-1, 200))
        assertEquals("input keyevent 4", PrivilegedCommandBuilder.keyEvent(4))
        assertNull(PrivilegedCommandBuilder.keyEvent(999))
    }

    @Test
    fun foregroundQueryDoesNotContainUserInput() {
        val command = PrivilegedCommandBuilder.foregroundPackage()
        assertFalse(command.isBlank())
        assertTrue(command.contains("dumpsys activity"))
    }

    @Test
    fun swipeRejectsUnsafeBoundsAndDurations() {
        assertEquals("input swipe 100 200 300 400 450", PrivilegedCommandBuilder.swipe(100, 200, 300, 400, 450))
        assertNull(PrivilegedCommandBuilder.swipe(-1, 200, 300, 400, 450))
        assertNull(PrivilegedCommandBuilder.swipe(100, 200, 300, 400, 10_000))
    }
}
