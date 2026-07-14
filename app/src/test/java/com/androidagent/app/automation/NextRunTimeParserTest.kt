package com.androidagent.app.automation

import org.junit.Assert.assertEquals
import org.junit.Test
import com.androidagent.app.agent.RuntimeOutcome
import com.androidagent.app.agent.RuntimeResult
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

    @Test
    fun workerMapsStructuredOutcomesWithoutStatusStringMatching() {
        assertEquals(
            WorkerDecisionType.SUCCESS,
            ScheduledWorkerResultMapper.map(RuntimeResult.failure(RuntimeOutcome.SUCCESS, "done"), true, false, false, 0).type,
        )
        assertEquals(
            WorkerDecisionType.RETRY,
            ScheduledWorkerResultMapper.map(RuntimeResult.failure(RuntimeOutcome.TRANSIENT_NETWORK_ERROR, "network"), true, false, false, 1).type,
        )
        assertEquals(
            WorkerDecisionType.FAILURE,
            ScheduledWorkerResultMapper.map(RuntimeResult.failure(RuntimeOutcome.SAFETY_BLOCKED, "blocked"), true, false, false, 0).type,
        )
        assertEquals(
            WorkerDecisionType.FAILURE,
            ScheduledWorkerResultMapper.map(RuntimeResult.failure(RuntimeOutcome.TRANSIENT_NETWORK_ERROR, "network"), true, false, false, 3).type,
        )
        assertEquals(
            WorkerDecisionType.FAILURE,
            ScheduledWorkerResultMapper.map(RuntimeResult.failure(RuntimeOutcome.USER_CANCELLED, "cancelled"), true, false, true, 0).let {
                assertEquals(RuntimeOutcome.TIMEOUT, it.outcome)
                it.type
            },
        )
        assertEquals(
            WorkerDecisionType.FAILURE,
            ScheduledWorkerResultMapper.map(null, false, false, false, 3).type,
        )
    }
}
