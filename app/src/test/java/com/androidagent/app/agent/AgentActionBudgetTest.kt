package com.androidagent.app.agent

import org.junit.Assert.assertEquals
import org.junit.Test

class AgentActionBudgetTest {
    @Test
    fun deviceAgentExposesFiftyRealActionTurns() {
        assertEquals(50, DEVICE_ACTION_TURN_LIMIT)
        assertEquals(DEVICE_ACTION_TURN_LIMIT, AgentUiState().maxSteps)
    }
}
