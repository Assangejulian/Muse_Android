package com.androidagent.app.agent

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RunScopedSideEffectLedgerTest {
    @Test
    fun sameClickIdentityStaysBlockedAcrossReplanAndWindowChanges() {
        val ledger = RunScopedSideEffectLedger("run-1")
        val before = observation(
            UiNodeSnapshot(
                id = 1,
                text = "Target",
                description = "",
                className = "Button",
                clickable = true,
                editable = false,
                bounds = "0,0,100,40",
                viewId = "app:id/target",
                treePath = listOf(0, 2),
                packageName = "primary.app",
                windowId = 10,
            ),
            windowId = 10,
        )
        val recreated = observation(
            before.nodes.single().copy(id = 99, windowId = 11),
            windowId = 11,
        )
        val first = SideEffectIdentityFactory.create(AgentAction.ClickNode(1, predicateId = "old-p1"), before)
        val second = SideEffectIdentityFactory.create(AgentAction.ClickNode(99, predicateId = "new-p9"), recreated)
        assertNotNull(first)
        assertEquals(first, second)
        assertTrue(ledger.markUnknown(first!!, before.observationId, dispatchSequence = 1))
        assertFalse(ledger.check(second!!).allowed)
    }

    @Test
    fun differentStructureAndToggleDesiredStateRemainIndependent() {
        val ledger = RunScopedSideEffectLedger("run-1")
        val firstObservation = observation(
            UiNodeSnapshot(1, "Target", "", "Switch", true, false, "0,0,100,40", checked = false, packageName = "primary.app", windowId = 1),
            windowId = 1,
        )
        val otherObservation = observation(
            UiNodeSnapshot(2, "Other", "", "Switch", true, false, "0,50,100,90", checked = false, viewId = "primary:id/other", treePath = listOf(1), packageName = "primary.app", windowId = 1),
            windowId = 1,
        )
        val targetOn = SideEffectIdentityFactory.create(AgentAction.EnsureToggle(1, true), firstObservation)!!
        val targetOff = SideEffectIdentityFactory.create(AgentAction.EnsureToggle(1, false), firstObservation)!!
        val otherOn = SideEffectIdentityFactory.create(AgentAction.EnsureToggle(2, true), otherObservation)!!
        ledger.markUnknown(targetOn, firstObservation.observationId, dispatchSequence = 1)
        assertTrue(ledger.check(targetOff).allowed)
        assertTrue(ledger.check(otherOn).allowed)
    }

    @Test
    fun failedDispatchDoesNotPermanentlyLockIdentity() {
        val ledger = RunScopedSideEffectLedger("run-1")
        val screen = observation(UiNodeSnapshot(1, "Target", "", "Button", true, false, "0,0,100,40", packageName = "primary.app"))
        val identity = SideEffectIdentityFactory.create(AgentAction.ClickNode(1), screen)!!
        ledger.markFailed(identity)
        assertTrue(ledger.check(identity).allowed)
        ledger.markConfirmed(identity, screen.observationId, dispatchSequence = 2)
        assertFalse(ledger.check(identity).allowed)
    }

    @Test
    fun preDispatchSnapshotIsSafeAndBounded() {
        val store = PreDispatchEvidenceStore(maxSnapshots = 8)
        val screen = observation(
            UiNodeSnapshot(1, "secret-value", "", "EditText", false, true, "0,0,100,40", packageName = "primary.app", password = true),
        )
        val identity = SideEffectIdentityFactory.create(AgentAction.InputText("secret-value", nodeId = 1), screen)!!
        repeat(10) { index ->
            store.capture(screen, identity, index.toLong())
        }
        assertEquals(8, store.all().size)
        assertEquals(null, store.all().last().nodes.single().safeText)
        assertFalse(store.all().last().nodes.single().textHash.contains("secret-value"))
    }

    @Test
    fun failedUnknownRecoveryAbortsInsteadOfReturningUnknown() = runBlocking {
        val plan = TaskPlan(
            summary = "dismiss",
            targetAppHint = "primary.app",
            goal = GoalContext("dismiss"),
            milestones = listOf(TaskMilestone(
                "m1",
                "dismiss target",
                listOf(UiPredicate(UiPredicateKind.ELEMENT_DISAPPEARED, predicateId = "gone", targetHint = "Target", description = "target disappears")),
            )),
        )
        val screen = observation(UiNodeSnapshot(1, "Target", "", "Button", true, false, "0,0,100,40", packageName = "primary.app"))
        val bindings = PredicateBindingStore()
        val sideEffects = RunScopedSideEffectLedger("run-1")
        val snapshots = PreDispatchEvidenceStore()
        val driver = object : RuntimeStepDriver {
            override suspend fun executeDetailed(action: AgentAction, observation: Observation) = ActionExecutionResult(true, "accepted")
            override suspend fun settle(before: Observation, action: AgentAction) = RuntimeStepSettleResult(DispatchResultState.RESULT_UNKNOWN, before, "timeout")
            override suspend fun executeRecovery(decision: RecoveryDecision, observation: Observation) = RuntimeStepRecoveryResult(false, observation, "recovery failed")
        }
        val result = RuntimeStepEngine(driver).execute(
            RuntimeStepRequest(
                step = 1,
                proposed = AgentAction.ClickNode(1),
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
            ),
        )
        assertEquals(RuntimeStepStatus.ABORTED, result.status)
        assertFalse(result.status == RuntimeStepStatus.RESULT_UNKNOWN)
        assertTrue(sideEffects.records().single().state == SideEffectResultState.UNKNOWN_SIDE_EFFECT)
    }

    private fun observation(node: UiNodeSnapshot, windowId: Int? = node.windowId): Observation = Observation(
        packageName = node.packageName,
        nodes = listOf(node),
        windowIds = windowId?.let(::setOf) ?: emptySet(),
        windowPackages = windowId?.let { mapOf(it to node.packageName) } ?: emptyMap(),
    )
}
