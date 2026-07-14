package com.androidagent.app.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeContractsTest {
    private val plan = TaskPlan(
        summary = "state contract",
        targetAppHint = "primary.app",
        goal = GoalContext("state contract"),
        milestones = listOf(
            TaskMilestone(
                "verify",
                "verify target",
                listOf(UiPredicate(UiPredicateKind.TOGGLE_ON, target = ElementSelector(text = "Target", className = "Switch"), description = "target is on")),
            ),
        ),
    )

    @Test
    fun anotherEnabledSwitchCannotProveTargetToggle() {
        val screen = Observation(
            "primary.app",
            listOf(
                UiNodeSnapshot(1, "Target", "", "Switch", true, false, "0,0,100,30", checked = false, packageName = "primary.app"),
                UiNodeSnapshot(2, "Other", "", "Switch", true, false, "0,40,100,70", checked = true, packageName = "primary.app"),
            ),
        )
        assertFalse(MilestoneEvaluator.evaluate(plan.milestones.single(), plan, screen, "primary.app").proven)
    }

    @Test
    fun duplicateTargetElementsRemainUnknown() {
        val screen = Observation(
            "primary.app",
            listOf(
                UiNodeSnapshot(1, "Target", "", "Switch", true, false, "0,0,100,30", checked = true, packageName = "primary.app"),
                UiNodeSnapshot(2, "Target", "", "Switch", true, false, "0,40,100,70", checked = true, packageName = "primary.app"),
            ),
        )
        assertFalse(MilestoneEvaluator.evaluate(plan.milestones.single(), plan, screen, "primary.app").proven)
    }

    @Test
    fun packagePredicateUsesItsOwnTargetPackage() {
        val milestone = TaskMilestone(
            "external",
            "external app",
            listOf(UiPredicate(UiPredicateKind.PACKAGE_FOREGROUND, targetPackage = "secondary.app", description = "secondary foreground")),
        )
        val screen = Observation("secondary.app", emptyList())
        assertTrue(MilestoneEvaluator.evaluate(milestone, plan, screen, "primary.app").proven)
    }

    @Test
    fun semanticOnlyPlansAreRejected() {
        val invalid = TaskPlan(
            summary = "invalid",
            targetAppHint = "primary.app",
            goal = GoalContext("invalid"),
            milestones = listOf(TaskMilestone("m1", "claim", listOf(UiPredicate(UiPredicateKind.SEMANTIC_CLAIM, description = "claim")))),
        )
        assertFalse(runCatching { TaskPlanValidator.requireValid(invalid) }.isSuccess)
    }
}
