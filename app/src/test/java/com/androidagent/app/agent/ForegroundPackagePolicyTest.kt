package com.androidagent.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundPackagePolicyTest {
    private val installed = setOf(
        "com.xingin.xhs",
        "com.android.settings",
        "com.android.launcher3",
        "com.miui.home",
        "com.android.permissioncontroller",
        "com.androidagent.app",
    )

    @Test
    fun adoptsARealForegroundAppWhenTheGoalDidNotNameOne() {
        assertEquals("com.xingin.xhs", ForegroundPackagePolicy.adopt("com.xingin.xhs", installed))
        assertEquals("com.android.settings", ForegroundPackagePolicy.adopt("com.android.settings", installed))
    }

    @Test
    fun leavesLaunchersAndProtectedSurfacesUnlocked() {
        assertNull(ForegroundPackagePolicy.adopt("com.android.launcher3", installed))
        assertNull(ForegroundPackagePolicy.adopt("com.miui.home", installed))
        assertNull(ForegroundPackagePolicy.adopt("com.android.systemui", installed))
        assertNull(ForegroundPackagePolicy.adopt("com.android.permissioncontroller", installed))
        assertNull(ForegroundPackagePolicy.adopt("com.androidagent.app", installed))
        assertNull(ForegroundPackagePolicy.adopt("com.unknown.app", installed))
        assertNull(ForegroundPackagePolicy.adopt("", installed))
    }

    @Test
    fun homeShellDetectionIsGeneric() {
        assertTrue(ForegroundPackagePolicy.isHomeShell("com.android.launcher3"))
        assertTrue(ForegroundPackagePolicy.isHomeShell("com.miui.home"))
        assertTrue(ForegroundPackagePolicy.isHomeShell("com.teslacoilsw.launcher"))
        assertFalse(ForegroundPackagePolicy.isHomeShell("com.xingin.xhs"))
        assertFalse(ForegroundPackagePolicy.isHomeShell("com.android.settings"))
    }
}
