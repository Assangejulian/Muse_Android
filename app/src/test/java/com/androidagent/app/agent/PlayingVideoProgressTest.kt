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
        val identity = first.resolvedTarget.let {
            SideEffectIdentityFactory.create(AgentAction.ClickNode(1), live, resolvedTarget = it)
        }!!
        // Same-page clicks stay retryable; only a page-leaving activation is explored.
        assertTrue(sideEffects.check(identity).allowed)

        val second = RuntimeStepEngine(driver).execute(request(live))
        assertEquals(RuntimeStepStatus.NO_PROGRESS, second.status)
        assertFalse(second.reason.contains("already followed"))
    }

    @Test
    fun checkedStateChangeOnTheSamePageCountsAsProgress() = runBlocking {
        val node = UiNodeSnapshot(
            1, "", "toggle", "Switch", true, false, "0,400,80,440",
            viewId = "app:id/like", treePath = listOf(0, 2), checked = false,
            packageName = "primary.app", windowId = 3,
        )
        var live = Observation("primary.app", listOf(node), windowIds = setOf(3), windowPackages = mapOf(3 to "primary.app"))
        val milestone = TaskMilestone("goal", "toggle", listOf(UiPredicate(UiPredicateKind.SEMANTIC_CLAIM, description = "on")))
        val plan = TaskPlan("toggle", "primary.app", GoalContext("toggle it"), listOf(milestone))
        val driver = object : RuntimeStepDriver {
            override suspend fun executeDetailed(action: AgentAction, observation: Observation): ActionExecutionResult =
                ActionExecutionResult(true, "clicked", "accepted")

            override suspend fun settle(before: Observation, action: AgentAction): RuntimeStepSettleResult {
                live = live.copy(nodes = live.nodes.map { it.copy(checked = true) })
                return RuntimeStepSettleResult(DispatchResultState.CONFIRMED, live, "checked flipped")
            }
        }
        val result = RuntimeStepEngine(driver).execute(
            RuntimeStepRequest(
                step = 1,
                proposed = AgentAction.ClickNode(1),
                planningObservation = live,
                executionObservation = live,
                plan = plan,
                milestone = milestone,
                guard = ToolGuard(plan, PackagePolicy(mutableSetOf("primary.app"), "primary.app")),
                ledger = RunLedger(plan),
                bindings = PredicateBindingStore(),
                recoveryPolicy = RecoveryPolicy(),
                packagePolicy = PackagePolicy(mutableSetOf("primary.app"), "primary.app"),
                launchablePackages = setOf("primary.app"),
                goal = plan.goal,
                targetPackage = "primary.app",
                evidenceCounters = StopGateEvidenceCounters(),
            ),
        )
        assertEquals(RuntimeStepStatus.PROGRESS, result.status)
    }
}
