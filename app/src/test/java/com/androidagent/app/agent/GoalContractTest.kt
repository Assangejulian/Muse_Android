package com.androidagent.app.agent

import org.junit.Assert.assertEquals
import org.junit.Test

class GoalContractTest {
    @Test
    fun extractsStableCreatorQueryFromImmutableGoal() {
        assertEquals("老番茄", GoalContract.extractSearchQuery("打开B站给老番茄的最新视频点赞"))
    }
}
