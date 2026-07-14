package com.androidagent.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class RuntimeGuardsTest {
    private val plan = TaskPlan(
        summary = "complete a task",
        targetAppHint = "example.app",
        goal = GoalContext("complete a task"),
        milestones = listOf(
            TaskMilestone("verify", "verify", listOf(UiPredicate(UiPredicateKind.TEXT_PRESENT, literal = "done", description = "done"))),
        ),
    )

    @Test
    fun inputPayloadIsPreservedExactly() {
        val screen = Observation("example.app", listOf(editable(1, focused = true)))
        val action = AgentAction.InputText("first value", 1)
        assertEquals(action, ToolGuard(plan).normalizeAndValidate(action, screen).action)
        val second = AgentAction.InputText("second value", 1)
        assertEquals(second, ToolGuard(plan).normalizeAndValidate(second, screen).action)
    }

    @Test
    fun selectorAmbiguityIsRejected() {
        val selector = ElementSelector(packageName = "example.app", text = "Same", className = "Button")
        val screen = Observation("example.app", listOf(
            UiNodeSnapshot(1, "Same", "", "Button", true, false, "0,0,100,30", packageName = "example.app"),
            UiNodeSnapshot(2, "Same", "", "Button", true, false, "0,40,100,70", packageName = "example.app"),
        ))
        assertNull(ToolGuard(plan).normalizeAndValidate(AgentAction.ClickNode(1, selector), screen).action)
    }

    @Test
    fun selectedStateIsTheOnlyGenericToggleProof() {
        val milestone = TaskMilestone(
            "verify",
            "verify",
            listOf(UiPredicate(UiPredicateKind.TOGGLE_ON, target = ElementSelector(className = "Switch"), description = "state")),
        )
        val selected = Observation("example.app", listOf(UiNodeSnapshot(1, "", "", "Switch", true, false, "0,0,100,30", selected = true)))
        val unselected = selected.copy(nodes = selected.nodes.map { it.copy(selected = false) })
        assertTrue(MilestoneEvaluator.evaluate(milestone, plan, selected, "example.app").proven)
        assertFalse(MilestoneEvaluator.evaluate(milestone, plan, unselected, "example.app").proven)
    }

    @Test
    fun blockedAttemptsDoNotConsumeDuplicateBudget() {
        val ledger = RunLedger(plan)
        val screen = Observation("example.app", listOf(UiNodeSnapshot(1, "", "", "Button", true, false, "0,0,100,30")))
        val action = AgentAction.ClickNode(1)
        assertNull(ledger.blockRepeated(action, screen))
        assertNull(ledger.blockRepeated(action, screen))
        ledger.recordDispatch(action, screen)
        assertNull(ledger.blockRepeated(action, screen))
        ledger.recordDispatch(action, screen)
        assertNotNull(ledger.blockRepeated(action, screen))
    }

    @Test
    fun detectsAbabCycleAndIgnoresDuplicateSamples() {
        val ledger = RunLedger(plan)
        fun screen(text: String) = Observation("example", listOf(UiNodeSnapshot(1, text, "", "Text", false, false, "0,0,100,30")))
        repeat(4) { ledger.observe(screen("same")) }
        assertNull(ledger.cyclePeriod())
        listOf(screen("a"), screen("b"), screen("a"), screen("b")).forEach(ledger::observe)
        assertEquals(2, ledger.cyclePeriod())
    }

    private fun editable(id: Int, focused: Boolean) = UiNodeSnapshot(id, "", "", "EditText", false, true, "0,0,500,80", focused = focused)
    private fun assertTrue(value: Boolean) = org.junit.Assert.assertTrue(value)
}
