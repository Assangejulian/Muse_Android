package com.androidagent.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class RuntimeGuardsTest {
    private val plan = TaskPlanParser.fallback("给老番茄最新视频点赞", "B站", "老番茄")

    @Test
    fun lockedValueCannotBeMutatedByActor() {
        val result = ToolGuard(plan).normalizeAndValidate(AgentAction.InputText("老番茄5", 1), observation("", editable = true))
        assertEquals(AgentAction.InputText("老番茄", 1), result.action)
    }

    @Test
    fun keyboardDigitNodeIsBlockedWhileQueryFieldIsActive() {
        val screen = Observation(
            "bili",
            listOf(
                UiNodeSnapshot(1, "老番茄", "", "EditText", false, true, "0,0,500,80", focused = true),
                UiNodeSnapshot(5, "5", "", "TextView", true, false, "400,700,500,800"),
            ),
        )

        val result = ToolGuard(plan).normalizeAndValidate(AgentAction.ClickNode(5), screen)

        assertNull(result.action)
        assertEquals("keyboard digit is unrelated to the locked query", result.rejection)
    }

    @Test
    fun blocksThirdSameStrategyBeforeExecution() {
        val ledger = RunLedger(plan)
        val observation = observation("首页")
        val action = AgentAction.ClickText("搜索")
        assertEquals(null, ledger.blockRepeated(action, observation))
        assertEquals(null, ledger.blockRepeated(action, observation))
        assertNotNull(ledger.blockRepeated(action, observation))
    }

    @Test
    fun inputPredicateNeedsExactReadback() {
        val milestone = plan.milestones.first { it.successPredicates.any { predicate -> predicate.kind == UiPredicateKind.EDITABLE_EQUALS } }
        val wrong = observation("老番茄5", editable = true)
        assertTrue(!MilestoneEvaluator.evaluate(milestone, plan, wrong, "bili").proven)
    }

    @Test
    fun unrelatedSelectedTabDoesNotProveLikeToggle() {
        val milestone = plan.milestones.last()
        val screen = Observation(
            "bili",
            listOf(UiNodeSnapshot(1, "首页", "", "Tab", true, false, "0,0,100,30", selected = true)),
        )
        assertTrue(!MilestoneEvaluator.evaluate(milestone, plan, screen, "bili").proven)
    }

    @Test
    fun likeControlStateProvesLikeToggle() {
        val milestone = plan.milestones.last()
        val screen = Observation(
            "bili",
            listOf(UiNodeSnapshot(1, "", "取消点赞", "Button", true, false, "0,0,100,30", selected = true)),
        )
        assertTrue(MilestoneEvaluator.evaluate(milestone, plan, screen, "bili").proven)
    }

    @Test
    fun selectedStateChangesObservationFingerprint() {
        val off = Observation("bili", listOf(UiNodeSnapshot(1, "点赞", "", "Button", true, false, "0,0,100,30", selected = false)))
        val on = Observation("bili", listOf(UiNodeSnapshot(1, "点赞", "", "Button", true, false, "0,0,100,30", selected = true)))
        assertTrue(off.observationId != on.observationId)
    }

    @Test
    fun duplicateSamplingIsNotMistakenForCycle() {
        val ledger = RunLedger(plan)
        val same = observation("首页")
        repeat(4) { ledger.observe(same) }
        assertNull(ledger.cyclePeriod())
    }

    @Test
    fun detectsTwoStateOscillation() {
        val ledger = RunLedger(plan)
        listOf(observation("首页"), observation("搜索"), observation("首页"), observation("搜索")).forEach(ledger::observe)
        assertEquals(2, ledger.cyclePeriod())
    }

    private fun observation(text: String, editable: Boolean = false) = Observation(
        "bili",
        listOf(UiNodeSnapshot(1, text, "", "EditText", true, editable, "0,0,100,30")),
    )
}
