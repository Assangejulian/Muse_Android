package com.androidagent.app.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivacyGuardTest {
    @Test
    fun blocksPasswordBeforeModelAccess() {
        val observation = Observation("example.app", listOf(UiNodeSnapshot(1, "secret", "password", "EditText", false, true, "0,0,100,30", password = true)))
        val decision = PrivacyGuard.prepare(observation)
        assertFalse(decision.allowed)
        assertTrue(decision.reason.orEmpty().contains("password"))
        assertFalse(decision.observation.visibleText().contains("secret"))
    }

    @Test
    fun blocksSensitiveSurfaceBeforeModelAccess() {
        val observation = Observation("shop.app", listOf(UiNodeSnapshot(1, "payment", "", "Button", true, false, "0,0,100,30")))
        assertFalse(PrivacyGuard.prepare(observation).allowed)
    }

    @Test
    fun redactsPhoneEmailIdCardBankCardAndOtpCandidates() {
        val observation = Observation("contacts.app", listOf(
            UiNodeSnapshot(1, "13800138000 user@example.com 11010519491231002X 6222021234567890", "", "TextView", false, false, "0,0,100,30"),
            UiNodeSnapshot(2, "123456", "verification code", "TextView", false, false, "0,40,100,70"),
        ))
        val sanitized = PrivacyGuard.sanitize(observation).visibleText()
        assertFalse(sanitized.contains("13800138000"))
        assertFalse(sanitized.contains("user@example.com"))
        assertFalse(sanitized.contains("11010519491231002X"))
        assertFalse(sanitized.contains("6222021234567890"))
        assertFalse(sanitized.contains("123456"))
        assertTrue(sanitized.contains("[redacted-"))
    }

    @Test
    fun ordinaryScreenCanReachModel() {
        assertTrue(PrivacyGuard.prepare(Observation("video.app", listOf(UiNodeSnapshot(1, "hello", "", "Button", true, false, "0,0,100,30")))).allowed)
    }
}
