package com.androidagent.app.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PackagePolicyTest {
    @Test
    fun allowsOnlyDeclaredTemporaryPackages() {
        val policy = PackagePolicy(
            allowedPackages = mutableSetOf("primary.app", "picker.app"),
            primaryPackage = "primary.app",
            allowTemporaryExternalPackages = false,
        )
        assertTrue(policy.allows("primary.app"))
        assertTrue(policy.allows("picker.app"))
        assertFalse(policy.allows("other.app"))
    }

    @Test
    fun systemUiIsNotImplicitlyAllowed() {
        assertFalse(PackagePolicy(primaryPackage = "primary.app").allows("com.android.systemui"))
        assertTrue(PackagePolicy(primaryPackage = "primary.app", allowSystemUi = true).allows("com.android.systemui"))
    }

    @Test
    fun installerAndPermissionControllerCannotBeAllowlistedByPlan() {
        val policy = PackagePolicy(
            allowedPackages = mutableSetOf("com.android.systemui", "com.android.packageinstaller", "com.android.permissioncontroller"),
            allowSystemUi = false,
        )
        assertFalse(policy.allows("com.android.systemui"))
        assertFalse(policy.allows("com.android.packageinstaller"))
        assertFalse(policy.allows("com.android.permissioncontroller"))
    }

    @Test
    fun plannerPackagesAreRestrictedToInstalledNonSystemApps() {
        assertEquals(
            setOf("secondary.app"),
            PackagePolicy.filterPlannerPackages(
                setOf("secondary.app", "missing.app", "com.android.systemui"),
                setOf("secondary.app", "com.android.systemui"),
            ),
        )
    }

    @Test
    fun addingPrimaryAndSecondaryPackagesKeepsTheOriginalSet() {
        assertEquals(
            setOf("primary.app", "secondary.app"),
            PackagePolicy.mergeAllowedPackages(
                current = setOf("primary.app"),
                requested = setOf("secondary.app"),
                installedPackages = setOf("primary.app", "secondary.app"),
            ),
        )
    }
}
