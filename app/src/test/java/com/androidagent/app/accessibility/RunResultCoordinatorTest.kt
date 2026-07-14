package com.androidagent.app.accessibility

import com.androidagent.app.agent.RuntimeOutcome
import com.androidagent.app.agent.RuntimeResult
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RunResultCoordinatorTest {
    @Test
    fun firstTerminalCauseWinsOverLaterShutdown() {
        val coordinator = RunResultCoordinator()
        coordinator.registerRun("run-1")

        assertEquals(
            AgentStopCause.WORKER_TIMEOUT,
            coordinator.recordStopCause("run-1", AgentStopCause.WORKER_TIMEOUT),
        )
        assertEquals(
            AgentStopCause.WORKER_TIMEOUT,
            coordinator.recordStopCause("run-1", AgentStopCause.APP_SHUTDOWN),
        )
        assertEquals(AgentStopCause.WORKER_TIMEOUT, coordinator.stopCauseFor("run-1"))
    }

    @Test
    fun accessibilityDisconnectIsNotOverwrittenByShutdown() {
        val coordinator = RunResultCoordinator()
        coordinator.registerRun("run-1")

        coordinator.recordStopCause("run-1", AgentStopCause.ACCESSIBILITY_DISCONNECTED)
        coordinator.recordStopCause("run-1", AgentStopCause.APP_SHUTDOWN)

        assertEquals(AgentStopCause.ACCESSIBILITY_DISCONNECTED, coordinator.stopCauseFor("run-1"))
    }

    @Test
    fun delayedTimeoutResultIsAwaitedAndConsumed() = runBlocking {
        val coordinator = RunResultCoordinator()
        coordinator.registerRun("run-1")
        coordinator.recordStopCause("run-1", AgentStopCause.WORKER_TIMEOUT)
        val expected = RuntimeResult.failure(RuntimeOutcome.TIMEOUT, "worker timeout", "run-1")

        val producer = async {
            delay(50)
            coordinator.storeResult("run-1", expected)
        }
        val actual = coordinator.awaitAndConsumeResult("run-1", 100)
        producer.await()

        assertEquals(expected, actual)
        assertNull(coordinator.resultForRun("run-1"))
        assertNull(coordinator.stopCauseFor("run-1"))
        assertEquals(0, coordinator.stats().waiters)
    }

    @Test
    fun tombstoneDropsLateResultAndAllRunMetadata() = runBlocking {
        val coordinator = RunResultCoordinator()
        coordinator.registerRun("run-1")
        coordinator.recordStopCause("run-1", AgentStopCause.WORKER_TIMEOUT)

        assertNull(coordinator.awaitAndConsumeResult("run-1", 20))
        coordinator.registerLateResultTombstone("run-1")
        assertFalse(
            coordinator.storeResult(
                "run-1",
                RuntimeResult.failure(RuntimeOutcome.TIMEOUT, "late", "run-1"),
            ),
        )

        assertNull(coordinator.resultForRun("run-1"))
        assertNull(coordinator.stopCauseFor("run-1"))
        assertEquals(RunResultCoordinatorStats(0, 0, 0, 1), coordinator.stats())
        assertFalse(
            coordinator.storeResult(
                "run-1",
                RuntimeResult.failure(RuntimeOutcome.INTERNAL_ERROR, "duplicate late result", "run-1"),
            ),
        )
        assertEquals(RunResultCoordinatorStats(0, 0, 0, 1), coordinator.stats())
    }

    @Test
    fun accessibilityDisconnectOutcomeIsDelivered() = runBlocking {
        val coordinator = RunResultCoordinator()
        coordinator.registerRun("run-1")
        coordinator.recordStopCause("run-1", AgentStopCause.ACCESSIBILITY_DISCONNECTED)
        val result = RuntimeResult.failure(
            RuntimeOutcome.ACCESSIBILITY_DISCONNECTED,
            "accessibility disconnected",
            "run-1",
        )

        assertTrue(coordinator.storeResult("run-1", result))
        assertEquals(RuntimeOutcome.ACCESSIBILITY_DISCONNECTED, coordinator.awaitAndConsumeResult("run-1", 100)?.outcome)
    }

    @Test
    fun userStopOutcomeIsDelivered() = runBlocking {
        val coordinator = RunResultCoordinator()
        coordinator.registerRun("run-1")
        coordinator.recordStopCause("run-1", AgentStopCause.USER_REQUEST)
        val result = RuntimeResult.failure(RuntimeOutcome.USER_CANCELLED, "cancelled", "run-1")

        assertTrue(coordinator.storeResult("run-1", result))
        assertEquals(RuntimeOutcome.USER_CANCELLED, coordinator.awaitAndConsumeResult("run-1", 100)?.outcome)
    }

    @Test
    fun distinctRunIdsNeverConsumeEachOthersResults() = runBlocking {
        val coordinator = RunResultCoordinator()
        coordinator.registerRun("run-1")
        coordinator.registerRun("run-2")
        val result1 = RuntimeResult.failure(RuntimeOutcome.USER_CANCELLED, "one", "run-1")
        val result2 = RuntimeResult.failure(RuntimeOutcome.TIMEOUT, "two", "run-2")

        coordinator.storeResult("run-2", result2)
        coordinator.storeResult("run-1", result1)

        assertEquals(result1, coordinator.awaitAndConsumeResult("run-1", 100))
        assertEquals(result2, coordinator.awaitAndConsumeResult("run-2", 100))
    }

    @Test
    fun coordinatorBoundsUnconsumedState() = runBlocking {
        val coordinator = RunResultCoordinator(
            maxRetainedResults = 2,
            maxRetainedTombstones = 2,
            maxRetainedWaiters = 2,
        )

        repeat(5) { index ->
            val runId = "run-$index"
            coordinator.registerRun(runId)
            coordinator.recordStopCause(runId, AgentStopCause.USER_REQUEST)
            assertNull(coordinator.awaitAndConsumeResult(runId, 1))
            coordinator.storeResult(
                runId,
                RuntimeResult.failure(RuntimeOutcome.USER_CANCELLED, "cancelled", runId),
            )
        }
        coordinator.registerLateResultTombstone("late-1")
        coordinator.registerLateResultTombstone("late-2")
        coordinator.registerLateResultTombstone("late-3")

        val stats = coordinator.stats()
        assertTrue(stats.results <= 2)
        assertTrue(stats.stopCauses <= 2)
        assertTrue(stats.waiters <= 2)
        assertTrue(stats.tombstones <= 2)
    }
}
