package com.androidagent.app.agent

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WaitEngineTest {
    @Test
    fun plainWaitCompletesWithoutRequiringScreenChange() = runBlocking {
        val screen = Observation("example.app", emptyList())
        val first = WaitEngine.waitForDuration(250) { screen }
        val second = WaitEngine.waitForDuration(500) { screen }
        assertTrue(first is WaitResult.Satisfied)
        assertTrue(second is WaitResult.Satisfied)
        assertEquals(screen.observationId, (first as WaitResult.Satisfied).value.observationId)
        assertEquals(screen.observationId, (second as WaitResult.Satisfied).value.observationId)
    }
}
