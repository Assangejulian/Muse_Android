package com.androidagent.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskPlanParserTest {
    @Test
    fun parserPreservesOriginalGoalAndIgnoresUnknownLegacyValue() {
        val raw = """{"summary":"test","targetAppHint":"example.app","milestones":[{"id":"m1","objective":"observe","successPredicates":[{"kind":"TEXT_PRESENT","literal":"ready","description":"visible evidence"}]}]}"""
        val plan = TaskPlanParser.parse(raw, "the complete original goal", "legacy value")
        assertEquals("the complete original goal", plan.originalGoal)
        assertEquals("example.app", plan.targetAppHint)
    }

    @Test
    fun deterministicFallbackLaunchesKnownPackageWithoutInventingContentSteps() {
        val plan = TaskPlanParser.fallback("open and complete", "example.app")
        assertEquals(1, plan.milestones.size)
        assertEquals(TaskMilestoneKind.LAUNCH_APP, plan.milestones.single().kind)
        assertEquals("example.app", plan.allowedPackages.single())
    }

    @Test
    fun parserRejectsSemanticOnlyMilestone() {
        val raw = """{"summary":"bad","milestones":[{"id":"m1","objective":"claim","successPredicates":[{"kind":"SEMANTIC_CLAIM","description":"claim"}]}]}"""
        assertThrows(TaskPlanException::class.java) {
            TaskPlanParser.parse(raw, GoalContext("bad"))
        }
    }

    @Test
    fun replanKeepsProvenPrefix() {
        val previous = validPlan()
        val repair = TaskMilestone("repair", "Repair missing proof", listOf(UiPredicate(UiPredicateKind.TEXT_PRESENT, literal = "proof", description = "proof")))
        val revised = previous.copy(milestones = previous.milestones + repair)
        val merged = revised.preserveCompletedPrefix(previous, 1)
        assertEquals(previous.milestones.first(), merged.milestones.first())
        assertTrue(merged.milestones.any { it.id == "repair" })
    }

    private fun validPlan() = TaskPlan(
        summary = "goal",
        targetAppHint = "example.app",
        goal = GoalContext("goal"),
        milestones = listOf(
            TaskMilestone("m1", "observe", listOf(UiPredicate(UiPredicateKind.TEXT_PRESENT, literal = "ready", description = "ready"))),
        ),
    )
}
