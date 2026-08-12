package com.androidagent.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class SecureSettingsTest {
    @Test
    fun defaultModelUsesRollingDeepSeekFlashAlias() {
        assertEquals("deepseek-v4-flash", SecureSettings.DEFAULT_MODEL)
    }

    @Test
    fun migratesPinnedFlashModelToRollingAlias() {
        assertEquals("deepseek-v4-flash", normalizeModelName("deepseek-v4-flash-0731"))
    }

    @Test
    fun preservesExplicitAlternativeModels() {
        assertEquals("deepseek-v4-pro", normalizeModelName("deepseek-v4-pro"))
    }

    @Test
    fun themeModeUsesSystemForUnknownStoredValues() {
        assertEquals(MuseThemeMode.SYSTEM, MuseThemeMode.fromStorage(null))
        assertEquals(MuseThemeMode.SYSTEM, MuseThemeMode.fromStorage("future-mode"))
        assertEquals(MuseThemeMode.LIGHT, MuseThemeMode.fromStorage("light"))
        assertEquals(MuseThemeMode.DARK, MuseThemeMode.fromStorage("dark"))
    }
}
