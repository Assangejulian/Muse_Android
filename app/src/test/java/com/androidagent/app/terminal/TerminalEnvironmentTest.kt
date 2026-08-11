package com.androidagent.app.terminal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test
    fun prependsEmbeddedLinuxShimsToEveryControlCommand() {
        val config = TerminalEnvironmentConfig(
            workingDirectory = "/sdcard",
            pathPrefix = "/custom/bin",
            enabledTools = setOf("node"),
            commands = mapOf("node" to "node"),
        )

        val wrapped = config.wrap("node --version")

        assertTrue(wrapped.contains("${EmbeddedLinuxEnvironment.SHIM_PATH}:/custom/bin"))
        assertTrue(wrapped.endsWith("node --version"))
    }

    @Test
    fun exposesOnlyPinnedChineseUbuntuArmMirrors() {
        assertEquals(setOf("tuna", "ustc", "bfsu"), EmbeddedLinuxEnvironment.mirrors.map { it.id }.toSet())
        assertTrue(EmbeddedLinuxEnvironment.mirrors.all { it.rootfsBaseUrl.startsWith("https://") })
        assertTrue(EmbeddedLinuxEnvironment.mirrors.all { it.aptBaseUrl.endsWith("/ubuntu-ports") })
        assertEquals(64, EmbeddedLinuxEnvironment.ROOTFS_SHA256.length)
    }

    @Test
    fun parsesInstalledEnvironmentManifestDefensively() {
        val installed = EmbeddedLinuxEnvironment.parseManifest(
            """{"version":"24.04.4","mirror":"tuna","tools":["node","python"]}""",
        )

        assertEquals("24.04.4", installed?.version)
        assertEquals(setOf("node", "python"), installed?.tools)
        assertNull(EmbeddedLinuxEnvironment.parseManifest("not-json"))
    }
}
