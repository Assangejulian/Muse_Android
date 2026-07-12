package com.androidagent.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskPlanParserTest {
    @Test
    fun canonicalQueryFromUserOverridesManagerMutation() {
        val raw = """{"summary":"test","targetAppHint":"B站","canonicalQuery":"老番茄5","milestones":[{"id":"m1","objective":"Search","successEvidence":"query visible"}]}"""
        val plan = TaskPlanParser.parse(raw, "goal", "老番茄")
        assertEquals("老番茄", plan.canonicalQuery)
    }

    @Test
    fun managerCannotInventCanonicalQuery() {
        val raw = """{"summary":"test","targetAppHint":"B站","canonicalQuery":"老番茄5","milestones":[{"id":"m1","objective":"Search","successEvidence":"query visible"}]}"""
        val plan = TaskPlanParser.parse(raw, "打开B站", null)
        assertEquals(null, plan.canonicalQuery)
    }

    @Test
    fun rejectsPredicateWithoutRequiredOperand() {
        val raw = """{"summary":"test","targetAppHint":"B站","milestones":[{"id":"m1","objective":"Search","successPredicates":[{"kind":"EDITABLE_EQUALS","description":"query"}]}]}"""
        assertTrue(runCatching { TaskPlanParser.parse(raw, "goal", "老番茄") }.isFailure)
    }

    @Test
    fun replanKeepsProvenPrefixAndReplacesPendingMilestones() {
        val previous = TaskPlanParser.fallback("打开B站给老番茄最新视频点赞", "B站", "老番茄")
        val repair = TaskMilestone("repair", "Repair missing proof", listOf(UiPredicate(UiPredicateKind.SEMANTIC_CLAIM, description = "Proof repaired")))
        val revised = previous.copy(milestones = previous.milestones + repair)
        val merged = revised.preserveCompletedPrefix(previous, 2)
        assertEquals(previous.milestones.take(2), merged.milestones.take(2))
        assertTrue(merged.milestones.any { it.id == "repair" })
    }

    @Test
    fun fallbackCreatesPlanWithFinalEvidence() {
        val plan = TaskPlanParser.fallback("打开B站给老番茄最新视频点赞", "B站", "老番茄")
        assertTrue(plan.milestones.size >= 5)
        assertTrue(plan.milestones.last().successEvidence.isNotBlank())
        assertEquals(2, plan.repairStartIndex())
    }
}
