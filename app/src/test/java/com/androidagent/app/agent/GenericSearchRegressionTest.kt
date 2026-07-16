package com.androidagent.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GenericSearchRegressionTest {
    @Test
    fun fallbackDoesNotInventInputOrContentMilestones() {
        val plan = TaskPlanParser.fallback("Complete a multi-step task", "example.app")
        assertEquals(1, plan.milestones.size)
        assertEquals(TaskMilestoneKind.LAUNCH_APP, plan.milestones.single().kind)
        assertFalse(plan.milestones.any { it.kind == TaskMilestoneKind.INPUT })
    }

    @Test
    fun twoDifferentInputActionsAreNotRewritten() {
        val plan = validPlan("enter two values")
        val screen = Observation("example.app", listOf(
            node(1, "", "Field A", "EditText", editable = true, focused = true),
        ))
        val guard = ToolGuard(plan)
        val first = AgentAction.InputText("alpha", 1)
        val second = AgentAction.InputText("beta", 1)
        assertEquals(first, guard.normalizeAndValidate(first, screen).action)
        assertEquals(second, guard.normalizeAndValidate(second, screen).action)
    }

    @Test
    fun multipleEditableNodesAreAmbiguous() {
        val plan = validPlan("enter values")
        val screen = Observation("example.app", listOf(
            node(1, "", "A", "EditText", editable = true),
            node(2, "", "B", "EditText", editable = true),
        ))
        val result = ToolGuard(plan).normalizeAndValidate(AgentAction.InputText("value"), screen)
        assertNull(result.action)
        assertTrue(result.rejection.orEmpty().contains("ambiguous"))
    }

    @Test
    fun dynamicClockTextDoesNotChurnFingerprintButActionabilityDoes() {
        val base = node(1, "Updated 12:30", "", "TextView")
        assertEquals(
            Observation("example", listOf(base)).observationId,
            Observation("example", listOf(base.copy(text = "Updated 12:31"))).observationId,
        )
        assertFalse(Observation("example", listOf(base)).observationId == Observation("example", listOf(base.copy(clickable = true))).observationId)
    }

    @Test
    fun nodesBeyondTheFirstHundredAffectFreshness() {
        val nodes = (1..110).map { id -> node(id, "item-$id", "", "TextView", bounds = "0,$id,100,${id + 1}") }
        val changed = nodes.toMutableList().also { it[109] = it[109].copy(text = "changed") }
        assertTrue(Observation("example", nodes).observationId != Observation("example", changed).observationId)
    }

    @Test
    fun twoClicksCannotCompleteWithoutEvidence() {
        val goal = GoalContext("complete task")
        val records = listOf(
            ActionRecord(1, AgentAction.ClickNode(1), true, result = "UI changed"),
            ActionRecord(2, AgentAction.ClickNode(2), true, result = "UI changed"),
        )
        val result = LocalCompletionEvaluator.evaluate(goal, records, null, Observation("example", emptyList()))
        assertFalse(result.completed)
    }

    @Test
    fun explicitEvidenceCanComplete() {
        val result = LocalCompletionEvaluator.evaluate(
            GoalContext("complete task"),
            listOf(ActionRecord(1, AgentAction.InputText("x"), true, result = "evidence: field equals x")),
            null,
            Observation("example", emptyList()),
        )
        assertTrue(result.completed)
    }

    private fun node(id: Int, text: String, description: String, className: String, editable: Boolean = false, focused: Boolean = false, clickable: Boolean = false, bounds: String = "0,0,100,30") =
        UiNodeSnapshot(id, text, description, className, clickable, editable, bounds, focused = focused)

    private fun validPlan(goal: String) = TaskPlan(
        summary = goal,
        targetAppHint = "example.app",
        goal = GoalContext(goal),
        milestones = listOf(TaskMilestone("m1", "verify", listOf(UiPredicate(UiPredicateKind.TEXT_PRESENT, literal = "done", description = "done")))),
    )
}
