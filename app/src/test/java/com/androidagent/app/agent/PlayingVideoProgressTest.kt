package com.androidagent.app.agent

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayingVideoProgressTest {
    @Test
    fun animatedTextIsNotProgressAndTheSameClickCannotRepeat() = runBlocking {
        val node = UiNodeSnapshot(
            1,
            "第一位评论用户",
            "",
            "Button",
            true,
            false,
            "0,400,200,440",
            viewId = "bili:id/user",
            treePath = listOf(0, 4),
            packageName = "tv.danmaku.bili",
            windowId = 3,
        )
        var live = Observation(
            "tv.danmaku.bili",
            listOf(node),
            windowIds = setOf(3),
            windowPackages = mapOf(3 to "tv.danmaku.bili"),
        )
        val milestone = TaskMilestone(
            "goal",
            "like the first comment",
            listOf(UiPredicate(UiPredicateKind.SEMANTIC_CLAIM, description = "liked", predicateId = "goal-state")),
        )
        val plan = TaskPlan("like", "tv.danmaku.bili", GoalContext("like the first comment"), listOf(milestone))
        val sideEffects = RunScopedSideEffectLedger("run-video")
        val driver = object : RuntimeStepDriver {
            override suspend fun executeDetailed(action: AgentAction, observation: Observation): ActionExecutionResult =
                ActionExecutionResult(true, "clicked", "accepted")

            override suspend fun settle(before: Observation, action: AgentAction): RuntimeStepSettleResult {
                live = live.copy(nodes = live.nodes.map { it.copy(text = "第一位评论用户 12:0${it.text.takeLast(1)}") })
                return RuntimeStepSettleResult(DispatchResultState.CONFIRMED, live, "video kept animating")
            }
        }
        fun request(screen: Observation) = RuntimeStepRequest(
            step = 1,
            proposed = AgentAction.ClickNode(1),
            planningObservation = screen,
            executionObservation = screen,
            plan = plan,
            milestone = milestone,
            guard = ToolGuard(plan, PackagePolicy(mutableSetOf("tv.danmaku.bili"), "tv.danmaku.bili")),
            ledger = RunLedger(plan),
            bindings = PredicateBindingStore(),
            recoveryPolicy = RecoveryPolicy(),
            packagePolicy = PackagePolicy(mutableSetOf("tv.danmaku.bili"), "tv.danmaku.bili"),
            launchablePackages = setOf("tv.danmaku.bili"),
            goal = plan.goal,
            targetPackage = "tv.danmaku.bili",
            evidenceCounters = StopGateEvidenceCounters(),
            runId = "run-video",
            sideEffects = sideEffects,
        )

        val first = RuntimeStepEngine(driver).execute(request(live))
        assertEquals(RuntimeStepStatus.NO_PROGRESS, first.status)
        assertFalse(sideEffects.check(first.resolvedTarget.let { SideEffectIdentityFactory.create(AgentAction.ClickNode(1), live, resolvedTarget = it) }!!).allowed)

        val second = RuntimeStepEngine(driver).execute(request(live))
        assertTrue(second.reason.contains("already followed"))
        assertFalse(second.status in setOf(RuntimeStepStatus.PROGRESS, RuntimeStepStatus.MILESTONE_COMPLETE))
    }
}
