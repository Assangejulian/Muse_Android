package com.androidagent.app.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
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

    @Test
    fun semanticClaimIsAuxiliaryWhenHardPredicateIsProven() {
        val milestone = TaskMilestone(
            "mixed",
            "show text",
            listOf(
                UiPredicate(UiPredicateKind.TEXT_PRESENT, literal = "ready", description = "ready is visible"),
                UiPredicate(UiPredicateKind.SEMANTIC_CLAIM, description = "model agrees"),
            ),
        )
        val mixedPlan = plan.copy(milestones = listOf(milestone))
        val screen = Observation("primary.app", listOf(UiNodeSnapshot(1, "ready", "", "TextView", false, false, "0,0,100,30", packageName = "primary.app")))
        assertTrue(MilestoneEvaluator.evaluate(milestone, mixedPlan, screen, "primary.app").proven)
    }

    @Test
    fun unboundTargetCannotProveBeforeActorBinding() {
        val targetMilestone = TaskMilestone(
            "toggle",
            "toggle target",
            listOf(UiPredicate(UiPredicateKind.TOGGLE_STATE, expectedChecked = true, targetHint = "Target", description = "target is checked")),
        )
        val targetPlan = plan.copy(milestones = listOf(targetMilestone))
        val screen = Observation("primary.app", listOf(
            UiNodeSnapshot(1, "Target", "", "Switch", true, false, "0,0,100,30", checked = true, packageName = "primary.app"),
        ))
        assertFalse(MilestoneEvaluator.evaluate(targetMilestone, targetPlan, screen, "primary.app").proven)

        val bindings = PredicateBindingStore()
        assertTrue(bindings.bindAction(targetMilestone, AgentAction.EnsureToggle(1, true), screen).bound)
        assertTrue(MilestoneEvaluator.evaluate(targetMilestone, targetPlan, screen, "primary.app", bindings).proven)
    }

    @Test
    fun typedToggleSupportsOffStateAndDoesNotScanOtherSwitches() {
        val milestone = TaskMilestone(
            "toggle",
            "toggle target off",
            listOf(UiPredicate(UiPredicateKind.TOGGLE_STATE, expectedChecked = false, target = ElementSelector(text = "Target", className = "Switch"), description = "target is off")),
        )
        val targetPlan = plan.copy(milestones = listOf(milestone))
        val screen = Observation("primary.app", listOf(
            UiNodeSnapshot(1, "Target", "", "Switch", true, false, "0,0,100,30", checked = false, packageName = "primary.app"),
            UiNodeSnapshot(2, "Other", "", "Switch", true, false, "0,40,100,70", checked = true, packageName = "primary.app"),
        ))
        val bindings = PredicateBindingStore()
        assertTrue(bindings.bind(milestone, 0, screen.nodes.first(), screen).bound)
        assertTrue(MilestoneEvaluator.evaluate(milestone, targetPlan, screen, "primary.app", bindings).proven)
    }

    @Test
    fun disappearedPredicateRequiresAConcretePreActionBinding() {
        val milestone = TaskMilestone(
            "dismiss",
            "dismiss target",
            listOf(UiPredicate(UiPredicateKind.ELEMENT_DISAPPEARED, targetHint = "Dismiss", description = "dismiss target disappeared")),
        )
        val plan = this.plan.copy(milestones = listOf(milestone))
        val before = Observation("primary.app", listOf(UiNodeSnapshot(1, "Dismiss", "", "Button", true, false, "0,0,100,30", packageName = "primary.app")))
        val after = Observation("primary.app", emptyList())
        val bindings = PredicateBindingStore()
        assertFalse(MilestoneEvaluator.evaluate(milestone, plan, after, "primary.app", bindings).proven)
        assertTrue(bindings.bindAction(milestone, AgentAction.ClickText("Dismiss"), before).bound)
        assertTrue(MilestoneEvaluator.evaluate(milestone, plan, after, "primary.app", bindings).proven)
    }

    @Test
    fun parserNormalizesLegacyToggleOnAndRejectsFuzzyElementState() {
        val legacy = """{"summary":"toggle","milestones":[{"id":"m1","objective":"toggle","successPredicates":[{"kind":"TOGGLE_ON","targetHint":"switch","description":"on"}]}]}"""
        val parsed = TaskPlanParser.parse(legacy, GoalContext("toggle"))
        assertEquals(UiPredicateKind.TOGGLE_STATE, parsed.milestones.single().successPredicates.single().kind)
        assertEquals(true, parsed.milestones.single().successPredicates.single().expectedChecked)
        val fuzzy = """{"summary":"state","milestones":[{"id":"m1","objective":"state","successPredicates":[{"kind":"ELEMENT_STATE","targetHint":"switch","literal":"on","description":"state"}]}]}"""
        assertFalse(runCatching { TaskPlanParser.parse(fuzzy, GoalContext("state")) }.isSuccess)
    }

    @Test
    fun textPresentDoesNotRequireEnabledFlag() {
        val milestone = TaskMilestone(
            "text",
            "show text",
            listOf(UiPredicate(UiPredicateKind.TEXT_PRESENT, literal = "visible", description = "visible text")),
        )
        val plan = this.plan.copy(milestones = listOf(milestone))
        val screen = Observation("primary.app", listOf(UiNodeSnapshot(1, "visible", "", "TextView", false, false, "0,0,100,30", enabled = false, packageName = "primary.app")))
        assertTrue(MilestoneEvaluator.evaluate(milestone, plan, screen, "primary.app").proven)
    }

    @Test
    fun replanRetainsValidBindingForAnIncompleteMilestone() {
        val predicate = UiPredicate(
            UiPredicateKind.ELEMENT_PRESENT,
            targetHint = "Target",
            description = "target remains present",
        )
        val previous = plan.copy(
            milestones = listOf(
                TaskMilestone("m1", "done", listOf(UiPredicate(UiPredicateKind.TEXT_PRESENT, literal = "done", description = "done"))),
                TaskMilestone("m2", "pending", listOf(predicate)),
            ),
        )
        val revised = previous.copy(
            milestones = previous.milestones + TaskMilestone(
                "m3",
                "new pending",
                listOf(UiPredicate(UiPredicateKind.TEXT_PRESENT, literal = "new", description = "new")),
            ),
        )
        val screen = Observation(
            "primary.app",
            listOf(UiNodeSnapshot(1, "Target", "", "Button", true, false, "0,0,100,30", packageName = "primary.app")),
        )
        val bindings = PredicateBindingStore()
        assertTrue(bindings.bind(previous.milestones[1], 0, screen.nodes.single(), screen).bound)
        bindings.retainCompleted(previous, revised, setOf("m1"))
        assertTrue(bindings.get("m2", 0) != null)
    }
}
