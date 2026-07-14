package com.androidagent.app.automation

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime

class NextRunTimeParserTest {
    private val now = LocalDateTime.of(2026, 7, 13, 20, 0)

    @Test
    fun parsesTomorrowTime() {
        assertEquals(LocalDateTime.of(2026, 7, 14, 8, 35), NextRunTimeParser.parse("tomorrow 08:35", now))
    }

    @Test
    fun parsesFullDate() {
        assertEquals(LocalDateTime.of(2026, 7, 14, 9, 5), NextRunTimeParser.parse("next run 2026-07-14 09:05", now))
    }

    @Test
    fun differentTaskIdsProduceDifferentStableWorkNames() {
        val first = ScheduledTaskScheduler.uniqueName("task-a")
        val second = ScheduledTaskScheduler.uniqueName("task-b")
        assertEquals(false, first == second)
    }

    @Test
    fun explicitScheduleCommandProducesRequestWithoutBusinessKeywords() {
        val request = ScheduleCommandParser.parse("/schedule ${System.currentTimeMillis() + 60_000}|open the calendar")
        assertEquals("open the calendar", request?.goal)
        assertEquals(true, request?.taskId?.startsWith("manual-") == true)
    }

    @Test
    fun malformedScheduleCommandDoesNotSilentlySchedule() {
        assertEquals(null, ScheduleCommandParser.parse("/schedule tomorrow|open app"))
        assertEquals(null, ScheduleCommandParser.parse("/schedule 12345"))
    }
}
