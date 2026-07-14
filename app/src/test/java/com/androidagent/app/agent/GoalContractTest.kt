package com.androidagent.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GoalContractTest {
    @Test
    fun preservesTheCompleteGoalWithoutGuessingFields() {
        val original = "Open an app, enter alpha, then enter beta in a different field"
        val context = ConservativeGoalInterpreter.interpret(original)
        assertEquals(original, context.originalGoal)
        assertNull(context.explicitAppHint)
        assertEquals(emptyList<String>(), context.constraints)
    }
}
