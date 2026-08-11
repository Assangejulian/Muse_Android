package com.androidagent.app.privileged

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files

class EnvironmentArchivePathTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun derivesHttpMirrorOnlyForSignedCaBootstrap() {
        assertEquals(
            "http://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports",
            caBootstrapMirrorUrl("https://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports"),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonHttpsBootstrapMirrorInput() {
        caBootstrapMirrorUrl("http://untrusted.example/ubuntu-ports")
    }

    @Test
    fun createsInstallDirectoryBeforeProbingStorage() {
        val installDirectory = temporaryFolder.root.toPath().resolve("missing/muse")

        val availableBytes = availableBytesAfterCreatingDirectory(installDirectory) { path ->
            assertTrue(Files.isDirectory(installDirectory))
            assertEquals(installDirectory.toString(), path)
            123L
        }

        assertEquals(123L, availableBytes)
    }

    @Test
    fun acceptsAppSpecificArchiveOnSupportedExternalMounts() {
        val suffix = "/Android/data/com.androidagent.app/files/environment-installer/" +
            "ubuntu-base-24.04.4-base-arm64.tar.gz"

        assertTrue(isAllowedEnvironmentArchivePath("/storage/emulated/0$suffix", APPLICATION_ID, ROOTFS_FILE))
        assertTrue(isAllowedEnvironmentArchivePath("/storage/1234-5678$suffix", APPLICATION_ID, ROOTFS_FILE))
        assertTrue(isAllowedEnvironmentArchivePath("/mnt/user/0/primary$suffix", APPLICATION_ID, ROOTFS_FILE))
        assertTrue(isAllowedEnvironmentArchivePath("/sdcard$suffix", APPLICATION_ID, ROOTFS_FILE))
    }

    @Test
    fun rejectsOtherPackagesLocationsAndFiles() {
        val valid = "/storage/emulated/0/Android/data/com.androidagent.app/files/environment-installer/" +
            "ubuntu-base-24.04.4-base-arm64.tar.gz"

        assertFalse(isAllowedEnvironmentArchivePath(valid.replace(APPLICATION_ID, "other.app"), APPLICATION_ID, ROOTFS_FILE))
        assertFalse(isAllowedEnvironmentArchivePath(valid.replace("/storage/", "/data/local/tmp/"), APPLICATION_ID, ROOTFS_FILE))
        assertFalse(isAllowedEnvironmentArchivePath(valid.replace("ubuntu-base", "other"), APPLICATION_ID, ROOTFS_FILE))
        assertFalse(isAllowedEnvironmentArchivePath(valid, "../com.androidagent.app", ROOTFS_FILE))
    }

    private companion object {
        const val APPLICATION_ID = "com.androidagent.app"
        const val ROOTFS_FILE = "ubuntu-base-24.04.4-base-arm64.tar.gz"
    }
}
