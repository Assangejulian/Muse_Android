package com.androidagent.app.accessibility

import com.androidagent.app.agent.RuntimeOutcome
import org.junit.Assert.assertEquals
import org.junit.Test

class AgentStopCausePolicyTest {
    @Test
    fun structuredStopCausesMapToDistinctRuntimeOutcomes() {
        assertEquals(RuntimeOutcome.USER_CANCELLED, AgentStopCausePolicy.outcome(AgentStopCause.USER_REQUEST))
        assertEquals(RuntimeOutcome.ACCESSIBILITY_DISCONNECTED, AgentStopCausePolicy.outcome(AgentStopCause.ACCESSIBILITY_INTERRUPTED))
        assertEquals(RuntimeOutcome.ACCESSIBILITY_DISCONNECTED, AgentStopCausePolicy.outcome(AgentStopCause.ACCESSIBILITY_DISCONNECTED))
        assertEquals(RuntimeOutcome.TIMEOUT, AgentStopCausePolicy.outcome(AgentStopCause.WORKER_TIMEOUT))
        assertEquals(RuntimeOutcome.USER_CANCELLED, AgentStopCausePolicy.outcome(AgentStopCause.APP_SHUTDOWN))
    }
}
