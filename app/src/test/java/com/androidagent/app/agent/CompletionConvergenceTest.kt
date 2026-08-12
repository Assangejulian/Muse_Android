package com.androidagent.app.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompletionConvergenceTest {
    private val observation = Observation("example.app", emptyList())

    @Test
    fun requiresConfirmedMutationAndCurrentObservation() {
        val counters = StopGateEvidenceCounters(successfulMutatingActions = 1)
        val current = ActionRecord(
            step = 1,
            action = AgentAction.ClickNode(1),
            success = true,
            afterFingerprint = observation.observationId,
            result = "confirmed",
        )

        assertTrue(canVerifyGoalConvergence(counters, current, observation))
        assertFalse(canVerifyGoalConvergence(StopGateEvidenceCounters(), current, observation))
        assertFalse(canVerifyGoalConvergence(counters, current.copy(success = false), observation))
        assertFalse(canVerifyGoalConvergence(counters, current.copy(afterFingerprint = "stale"), observation))
    }
}
