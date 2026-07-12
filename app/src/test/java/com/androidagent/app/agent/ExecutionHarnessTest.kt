package com.androidagent.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class ExecutionHarnessTest {
    @Test
    fun extractsStableCreatorQuery() {
        assertEquals("老番茄", GoalContract.extractSearchQuery("打开B站给老番茄的最新视频点赞"))
    }

    @Test
    fun normalizesMutatedModelInput() {
        val harness = ExecutionHarness("打开B站给老番茄的最新视频点赞")
        assertEquals(AgentAction.InputText("老番茄"), harness.normalize(AgentAction.InputText("老番茄5")))
    }

    @Test
    fun detectsTwoScreenCycleAndProvidesRecovery() {
        val harness = ExecutionHarness("搜索老番茄最新视频")
        val a = observation("home")
        val b = observation("search")
        listOf(a, b, a, b).forEach(harness::observe)
        assertNotNull(harness.recoveryAction(b))
    }

    private fun observation(text: String) = Observation("bili", listOf(UiNodeSnapshot(1, text, "", "TextView", true, false, "0,0,10,10")))
}
