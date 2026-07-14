package com.androidagent.app.agent

import org.junit.Assert.assertFalse
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
}
