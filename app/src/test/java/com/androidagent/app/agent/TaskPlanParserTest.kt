package com.androidagent.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskPlanParserTest {
    @Test
    fun parserPreservesOriginalGoalAndIgnoresUnknownLegacyValue() {
        val raw = """{"summary":"test","targetAppHint":"example.app","milestones":[{"id":"m1","objective":"observe","successEvidence":"visible evidence"}]}"""
        val plan = TaskPlanParser.parse(raw, "the complete original goal", "legacy value")
        assertEquals("the complete original goal", plan.originalGoal)
        assertEquals("example.app", plan.targetAppHint)
    }

    @Test
    fun fallbackHasOnlyGenericMilestones() {
        val plan = TaskPlanParser.fallback("open and complete", "example.app")
        assertEquals(listOf(TaskMilestoneKind.LAUNCH_APP, TaskMilestoneKind.VERIFICATION), plan.milestones.map { it.kind })
        assertTrue(plan.milestones.last().successEvidence.isNotBlank())
    }

    @Test
    fun replanKeepsProvenPrefix() {
        val previous = TaskPlanParser.fallback("goal", "example.app")
        val repair = TaskMilestone("repair", "Repair missing proof", listOf(UiPredicate(UiPredicateKind.SEMANTIC_CLAIM, description = "proof")))
        val revised = previous.copy(milestones = previous.milestones + repair)
        val merged = revised.preserveCompletedPrefix(previous, 1)
        assertEquals(previous.milestones.first(), merged.milestones.first())
        assertTrue(merged.milestones.any { it.id == "repair" })
    }
}
