package com.androidagent.app.agent

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundWindowReplanTest {
    @Test
    fun disappearedWindowWithoutStrongPostconditionRequiresReplanThenNewPredicateCanProve() = runBlocking {
        val previous = plan(includeReadyPredicate = false)
        val original = node(1, "Dismiss", windowId = 10, path = listOf(0, 1))
        val before = Observation("primary.app", listOf(original), windowIds = setOf(10), windowPackages = mapOf(10 to "primary.app"))
        val after = Observation(
            "primary.app",
            listOf(node(3, "Ready", windowId = 11, path = listOf(1, 0), className = "TextView", clickable = false)),
            windowIds = setOf(11),
            windowPackages = mapOf(11 to "primary.app"),
        )
        val bindings = PredicateBindingStore()
        val sideEffects = RunScopedSideEffectLedger("run-1")
        val snapshots = PreDispatchEvidenceStore()
        val result = RuntimeStepEngine(OneShotDriver(after)).execute(request(previous, before, bindings, sideEffects, snapshots))
        assertEquals(RuntimeStepStatus.REPLAN_REQUIRED, result.status)
        assertEquals(MilestoneEvaluator.BOUND_WINDOW_GONE_REPLAN_REASON, result.reason)

        val revised = plan(includeReadyPredicate = true)
        bindings.retainCompleted(previous, revised, emptySet())
        val proof = MilestoneEvaluator.evaluate(
            revised.milestones.single(),
            revised,
            after,
            "primary.app",
            bindings,
            runId = "run-1",
            preDispatchSnapshots = snapshots,
        )
        assertTrue(proof.proven)
        assertFalse(proof.replanRequired)
    }

    @Test
    fun newStrongPredicateAlreadyTrueBeforeDispatchDoesNotUnlockGoneWindow() = runBlocking {
        val previous = plan(includeReadyPredicate = false)
        val original = node(1, "Dismiss", windowId = 10, path = listOf(0, 1))
        val readyBefore = node(2, "Ready", windowId = 10, path = listOf(0, 2), className = "TextView", clickable = false)
        val before = Observation("primary.app", listOf(original, readyBefore), windowIds = setOf(10), windowPackages = mapOf(10 to "primary.app"))
        val after = Observation(
            "primary.app",
            listOf(node(3, "Ready", windowId = 11, path = listOf(1, 0), className = "TextView", clickable = false)),
            windowIds = setOf(11),
            windowPackages = mapOf(11 to "primary.app"),
        )
        val bindings = PredicateBindingStore()
        val sideEffects = RunScopedSideEffectLedger("run-1")
        val snapshots = PreDispatchEvidenceStore()
        val result = RuntimeStepEngine(OneShotDriver(after)).execute(request(previous, before, bindings, sideEffects, snapshots))
        assertEquals(RuntimeStepStatus.REPLAN_REQUIRED, result.status)
        val revised = plan(includeReadyPredicate = true)
        bindings.retainCompleted(previous, revised, emptySet())
        val proof = MilestoneEvaluator.evaluate(
            revised.milestones.single(),
            revised,
            after,
            "primary.app",
            bindings,
            runId = "run-1",
            preDispatchSnapshots = snapshots,
        )
        assertFalse(proof.proven)
        assertTrue(proof.replanRequired)
    }

    private fun request(
        plan: TaskPlan,
        screen: Observation,
        bindings: PredicateBindingStore,
        sideEffects: RunScopedSideEffectLedger,
        snapshots: PreDispatchEvidenceStore,
    ): RuntimeStepRequest = RuntimeStepRequest(
        step = 1,
        proposed = AgentAction.ClickNode(1, predicateId = "gone-p1"),
        planningObservation = screen,
        executionObservation = screen,
        plan = plan,
        milestone = plan.milestones.single(),
        guard = ToolGuard(plan, PackagePolicy(mutableSetOf("primary.app"), "primary.app")),
        ledger = RunLedger(plan),
        bindings = bindings,
        recoveryPolicy = RecoveryPolicy(),
        packagePolicy = PackagePolicy(mutableSetOf("primary.app"), "primary.app"),
        launchablePackages = setOf("primary.app"),
        goal = plan.goal,
        targetPackage = "primary.app",
        evidenceCounters = StopGateEvidenceCounters(),
        runId = "run-1",
        sideEffects = sideEffects,
        preDispatchSnapshots = snapshots,
    )

    private fun plan(includeReadyPredicate: Boolean): TaskPlan {
        val predicates = mutableListOf(
            UiPredicate(
                kind = UiPredicateKind.ELEMENT_DISAPPEARED,
                predicateId = "gone-p1",
                targetHint = "Dismiss",
                description = "dialog disappeared",
            ),
        )
        if (includeReadyPredicate) {
            predicates += UiPredicate(
                kind = UiPredicateKind.TEXT_PRESENT,
                predicateId = "ready-p2",
                literal = "Ready",
                description = "ready state is visible",
            )
        }
        return TaskPlan(
            summary = "dismiss",
            targetAppHint = "primary.app",
            goal = GoalContext("dismiss"),
            milestones = listOf(TaskMilestone("m1", "dismiss target", predicates)),
        )
    }

    private fun node(
        id: Int,
        text: String,
        windowId: Int,
        path: List<Int>,
        className: String = "Button",
        clickable: Boolean = true,
    ) = UiNodeSnapshot(
        id = id,
        text = text,
        description = "",
        className = className,
        clickable = clickable,
        editable = false,
        bounds = "0,0,100,40",
        viewId = "primary:id/${text.lowercase()}",
        treePath = path,
        packageName = "primary.app",
        windowId = windowId,
    )

    private class OneShotDriver(private val after: Observation) : RuntimeStepDriver {
        override suspend fun executeDetailed(action: AgentAction, observation: Observation) = ActionExecutionResult(true, "accepted")
        override suspend fun settle(before: Observation, action: AgentAction) = RuntimeStepSettleResult(DispatchResultState.CONFIRMED, after, "settled")
        override suspend fun executeRecovery(decision: RecoveryDecision, observation: Observation) = RuntimeStepRecoveryResult(true, after, decision.action.name)
    }
}
