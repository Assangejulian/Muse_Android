package com.androidagent.app.agent

import org.junit.Assert.assertTrue
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
}
