package com.androidagent.app.agent

import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class SafetyGuardTest {
    private val sensitiveScreen = Observation(
        "unexpected.package",
        listOf(UiNodeSnapshot(1, "payment", "", "TextView", false, false, "0,0,100,30")),
    )

    @Test
    fun recoveryActionsRemainAvailableOnSensitiveOrUnexpectedPages() {
        val packages = setOf("primary.app")
        assertTrue(SafetyGuard.validate(AgentAction.Back, sensitiveScreen, "primary.app", packages).isSuccess)
        assertTrue(SafetyGuard.validate(AgentAction.Wait(500), sensitiveScreen, "primary.app", packages).isSuccess)
        assertTrue(SafetyGuard.validate(AgentAction.LaunchApp("primary.app"), sensitiveScreen, "primary.app", packages).isSuccess)
    }

    @Test
    fun mutatingActionsStayBlockedOnSensitivePages() {
        val packages = setOf("primary.app")
        assertTrue(SafetyGuard.validate(AgentAction.ClickText("payment"), sensitiveScreen, "primary.app", packages).isFailure)
        assertTrue(SafetyGuard.validate(AgentAction.TapPoint(500, 500), sensitiveScreen, "primary.app", packages).isFailure)
    }

    @Test
    fun sharedPolicyCoversChineseAndEnglishGoalTerms() {
        assertTrue(SensitiveOperationPolicy.validateGoal("payment now").isFailure)
        assertTrue(SensitiveOperationPolicy.validateGoal("\u652f\u4ed8\u4e00\u4e7e\u5143").isFailure)
        assertTrue(SensitiveOperationPolicy.validateGoal("Transfer to a bank card").isFailure)
        assertTrue(SensitiveOperationPolicy.validateGoal("normal navigation").isSuccess)
    }

    @Test
    fun sensitivePageAllowsOnlyRecoveryOrTerminationActions() {
        val packages = setOf("primary.app")
        listOf(AgentAction.Back, AgentAction.Home, AgentAction.Wait(250), AgentAction.Finish("stop"), AgentAction.Fail("stop"))
            .forEach { assertTrue(SafetyGuard.validate(it, sensitiveScreen, "primary.app", packages).isSuccess) }
        assertFalse(SafetyGuard.validate(AgentAction.InputText("safe"), sensitiveScreen, "primary.app", packages).isSuccess)
        assertFalse(SafetyGuard.validate(AgentAction.Swipe("up"), sensitiveScreen, "primary.app", packages).isSuccess)
        assertFalse(SafetyGuard.validate(AgentAction.EnsureToggle(1, true), sensitiveScreen, "primary.app", packages).isSuccess)
    }

    @Test
    fun targetSelectorMetadataIsCheckedBeforeClick() {
        val screen = Observation(
            "primary.app",
            listOf(UiNodeSnapshot(7, "", "", "EditText", true, true, "0,0,100,30", viewId = "password_field", packageName = "primary.app")),
        )
        val action = AgentAction.ClickNode(
            7,
            ElementSelector(viewIdResourceName = "password_field", packageName = "primary.app"),
        )
        assertTrue(SafetyGuard.validate(action, screen, "primary.app", setOf("primary.app")).isFailure)
    }

    @Test
    fun explicitlyDeclaredTemporaryPackageCanBeLaunched() {
        val policy = PackagePolicy(
            allowedPackages = mutableSetOf("primary.app"),
            primaryPackage = "primary.app",
            allowTemporaryExternalPackages = true,
            temporaryPackages = setOf("picker.app"),
        )
        assertTrue(
            SafetyGuard.validate(
                AgentAction.LaunchApp("picker.app"),
                Observation("primary.app", emptyList()),
                policy,
                setOf("primary.app", "picker.app"),
            ).isSuccess,
        )
    }

    @Test
    fun firstInstalledLaunchCanEstablishPrimaryPackage() {
        assertTrue(
            SafetyGuard.validate(
                AgentAction.LaunchApp("new.app"),
                Observation("launcher", emptyList()),
                PackagePolicy(),
                setOf("new.app"),
            ).isSuccess,
        )
    }
}
