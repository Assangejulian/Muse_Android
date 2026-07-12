package com.androidagent.app.agent

import org.junit.Assert.assertTrue
import org.junit.Test

class SafetyGuardTest {
    private val sensitiveScreen = Observation(
        "unexpected.package",
        listOf(UiNodeSnapshot(1, "购买会员", "", "TextView", false, false, "0,0,100,30")),
    )

    @Test
    fun recoveryActionsRemainAvailableOnSensitiveOrUnexpectedPages() {
        val packages = setOf("tv.danmaku.bili")
        assertTrue(SafetyGuard.validate(AgentAction.Back, sensitiveScreen, "tv.danmaku.bili", packages).isSuccess)
        assertTrue(SafetyGuard.validate(AgentAction.Wait(500), sensitiveScreen, "tv.danmaku.bili", packages).isSuccess)
        assertTrue(SafetyGuard.validate(AgentAction.LaunchApp("tv.danmaku.bili"), sensitiveScreen, "tv.danmaku.bili", packages).isSuccess)
    }

    @Test
    fun mutatingActionsStayBlockedOnSensitivePages() {
        val packages = setOf("tv.danmaku.bili")
        assertTrue(SafetyGuard.validate(AgentAction.ClickText("购买会员"), sensitiveScreen, "tv.danmaku.bili", packages).isFailure)
        assertTrue(SafetyGuard.validate(AgentAction.TapPoint(500, 500), sensitiveScreen, "tv.danmaku.bili", packages).isFailure)
    }
}
