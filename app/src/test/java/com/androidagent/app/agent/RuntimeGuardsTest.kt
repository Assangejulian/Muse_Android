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
    fun keyboardDigitNodeIsReplacedWithSafeDismissal() {
        val screen = Observation(
            "bili",
            listOf(
                UiNodeSnapshot(5, "5", "", "TextView", true, false, "400,700,500,800", packageName = "com.sohu.inputmethod.sogou", isInputMethod = true),
            ),
            imeVisible = true,
        )

        val result = ToolGuard(plan).normalizeAndValidate(AgentAction.ClickNode(5), screen, plan.milestones.first { it.kind == TaskMilestoneKind.SELECT_ENTITY })

        assertEquals(AgentAction.Back, result.action)
    }

    @Test
    fun dirtyLockedQueryIsRepairedBeforeActorRuns() {
        val screen = Observation(
            "bili",
            listOf(UiNodeSnapshot(1, "老番茄5", "", "EditText", false, true, "0,0,500,80", focused = true, packageName = "bili")),
            imeVisible = true,
        )
        val milestone = plan.milestones.first { it.kind == TaskMilestoneKind.SELECT_ENTITY }
        assertEquals(AgentAction.InputText("老番茄", 1), ToolGuard(plan).requiredWorkflowAction(screen, milestone))
    }

    @Test
    fun exactLockedQueryIsSubmittedBeforeActorRuns() {
        val screen = Observation(
            "bili",
            listOf(UiNodeSnapshot(1, "老番茄", "", "EditText", false, true, "0,0,500,80", focused = true, packageName = "bili")),
            imeVisible = true,
        )
        val milestone = plan.milestones.first { it.kind == TaskMilestoneKind.SELECT_ENTITY }
        assertEquals(AgentAction.SubmitInput(1), ToolGuard(plan).requiredWorkflowAction(screen, milestone))
    }

    @Test
    fun legitimateQueryContainingDigitsIsNotRejected() {
        val digitPlan = TaskPlanParser.fallback("搜索3Blue1Brown最新视频", "B站", "3Blue1Brown")
        val screen = Observation(
            "bili",
            listOf(UiNodeSnapshot(1, "3Blue1Brown", "", "EditText", false, true, "0,0,500,80", focused = true, packageName = "bili")),
            imeVisible = true,
        )
        val milestone = digitPlan.milestones.first { it.kind == TaskMilestoneKind.SELECT_ENTITY }
        assertEquals(AgentAction.SubmitInput(1), ToolGuard(digitPlan).requiredWorkflowAction(screen, milestone))
    }

    @Test
    fun lockedQueryIsNotForcedIntoLaterCommentFields() {
        val screen = Observation(
            "bili",
            listOf(UiNodeSnapshot(1, "", "写评论", "EditText", false, true, "0,0,500,80", focused = true, packageName = "bili")),
            imeVisible = true,
        )
        val milestone = plan.milestones.last()
        assertEquals(AgentAction.Back, ToolGuard(plan).requiredWorkflowAction(screen, milestone))
    }

    @Test
    fun unrelatedSearchSuggestionIsBlockedDuringEntitySelection() {
        val milestone = plan.milestones.first { it.kind == TaskMilestoneKind.SELECT_ENTITY }
        val screen = Observation("bili", listOf(UiNodeSnapshot(2, "英国史", "", "TextView", true, false, "0,0,100,30", packageName = "bili")))
        val result = ToolGuard(plan).normalizeAndValidate(AgentAction.ClickText("英国史"), screen, milestone)
        assertNull(result.action)
    }

    @Test
    fun actorBackIsBlockedDuringEntitySelectionWithoutImeRecovery() {
        val milestone = plan.milestones.first { it.kind == TaskMilestoneKind.SELECT_ENTITY }
        val screen = Observation("bili", listOf(UiNodeSnapshot(2, "英国史", "", "TextView", true, false, "0,0,100,30", packageName = "bili")))
        assertNull(ToolGuard(plan).normalizeAndValidate(AgentAction.Back, screen, milestone).action)
    }

    @Test
    fun clickableRowWithLockedEntityContextIsAllowed() {
        val milestone = plan.milestones.first { it.kind == TaskMilestoneKind.SELECT_ENTITY }
        val screen = Observation(
            "bili",
            listOf(
                UiNodeSnapshot(2, "", "", "Row", true, false, "0,0,500,100", treePath = "w0/1", packageName = "bili"),
                UiNodeSnapshot(3, "老番茄", "", "TextView", false, false, "10,10,200,60", treePath = "w0/1/0", packageName = "bili"),
            ),
        )
        assertEquals(AgentAction.ClickNode(2), ToolGuard(plan).normalizeAndValidate(AgentAction.ClickNode(2), screen, milestone).action)
    }

    @Test
    fun textPresentDoesNotUseEditableOrImeContent() {
        val milestone = TaskMilestone(
            "result",
            "Result",
            listOf(UiPredicate(UiPredicateKind.TEXT_PRESENT, valueRef = "canonical_query", description = "result label")),
        )
        val screen = Observation(
            "bili",
            listOf(
                UiNodeSnapshot(1, "老番茄", "", "EditText", false, true, "0,0,500,80", packageName = "bili"),
                UiNodeSnapshot(2, "老番茄", "", "TextView", false, false, "0,500,500,580", packageName = "ime", isInputMethod = true),
            ),
            imeVisible = true,
        )
        assertTrue(!MilestoneEvaluator.evaluate(milestone, plan, screen, "bili").proven)
    }

    @Test
    fun entityMilestoneHardGateRequiresResultLabelAndHiddenIme() {
        val milestone = plan.milestones.first { it.kind == TaskMilestoneKind.SELECT_ENTITY }
        val covered = Observation(
            "bili",
            listOf(
                UiNodeSnapshot(2, "老番茄", "", "TextView", true, false, "0,0,500,80", treePath = "w0/1/0/0", packageName = "bili"),
                UiNodeSnapshot(3, "1200万粉丝", "", "TextView", false, false, "0,90,500,140", treePath = "w0/1/0/1", packageName = "bili"),
                UiNodeSnapshot(4, "动态", "", "Tab", true, false, "0,150,200,200", treePath = "w0/2", packageName = "bili", viewId = "space_tab"),
                UiNodeSnapshot(5, "", "搜索", "EditText", true, true, "200,150,500,200", treePath = "w0/3", packageName = "bili", viewId = "search_text", focused = false),
            ),
            imeVisible = true,
        )
        val visible = covered.copy(imeVisible = false)
        assertTrue(!MilestoneEvaluator.evaluateHardPredicates(milestone, plan, covered, "bili").proven)
        assertTrue(MilestoneEvaluator.evaluateHardPredicates(milestone, plan, visible, "bili").proven)
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
    fun finalToggleCannotBeDispatchedTwiceWithoutProof() {
        val ledger = RunLedger(plan)
        repeat(plan.milestones.lastIndex) { ledger.advance("test evidence") }
        val screen = Observation("bili", listOf(UiNodeSnapshot(9, "点赞", "", "Button", true, false, "0,0,100,30")))
        val action = AgentAction.EnsureToggle(9, true)
        assertNull(ledger.blockRepeated(action, screen))
        ledger.recordDispatch(action)
        assertNotNull(ledger.blockRepeated(action, screen))
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
            listOf(
                UiNodeSnapshot(1, "", "取消点赞", "Button", true, false, "0,0,100,30", selected = true),
                UiNodeSnapshot(2, "老番茄", "", "TextView", false, false, "0,40,100,70", treePath = "w0/1/0/0"),
                UiNodeSnapshot(3, "关注", "", "Button", true, false, "110,40,180,70", treePath = "w0/1/0/1"),
                UiNodeSnapshot(4, "评论 1024", "", "Button", true, false, "0,80,100,110", treePath = "w0/2"),
                UiNodeSnapshot(5, "", "搜索", "EditText", true, true, "200,80,500,110", treePath = "w0/3", viewId = "search_text", focused = false),
            ),
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
