package com.androidagent.app.agent

import org.junit.Assert.assertEquals
import org.junit.Test

class CompletionConvergenceTest {
    @Test
    fun advisoryPlanLeavesLiveRouteToActor() {
        val plan = TaskPlanParser.advisory(
            GoalContext("complete the user goal"),
            "example.app",
        )

        assertEquals(1, plan.milestones.size)
        assertEquals(TaskMilestoneKind.GENERIC, plan.milestones.single().kind)
        assertEquals(UiPredicateKind.SEMANTIC_CLAIM, plan.milestones.single().successPredicates.single().kind)
    }
}
