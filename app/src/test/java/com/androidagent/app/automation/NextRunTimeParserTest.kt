package com.androidagent.app.automation

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime

class NextRunTimeParserTest {
    private val now = LocalDateTime.of(2026, 7, 13, 20, 0)

    @Test
    fun parsesTomorrowTime() {
        assertEquals(LocalDateTime.of(2026, 7, 14, 8, 35), NextRunTimeParser.parse("明天 08:35 可领取", now))
    }

    @Test
    fun parsesFullDate() {
        assertEquals(LocalDateTime.of(2026, 7, 14, 9, 5), NextRunTimeParser.parse("下次领取 2026-07-14 09:05", now))
    }
}
