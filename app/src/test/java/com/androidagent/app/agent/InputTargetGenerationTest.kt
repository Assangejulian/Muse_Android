package com.androidagent.app.agent

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InputTargetGenerationTest {
    @Test
    fun newInputGenerationAllowsNewSubmitButSameGenerationCannotRepeat() {
        val screen = inputScreen()
        val target = TargetResolver.resolveActionTarget(AgentAction.InputText("first", nodeId = 1), screen).target!!
        val ledger = RunScopedSideEffectLedger("run-input")

        val firstAction = AgentAction.InputText("first", nodeId = 1, submit = true)
        val firstReservation = ledger.prepareInputGeneration(firstAction, target)
        val firstIdentity = SideEffectIdentityFactory.create(
            firstAction,
            screen,
            resolvedTarget = target,
            inputGeneration = firstReservation.generation,
        )!!
        assertTrue(ledger.markConfirmed(firstIdentity, inputGenerationReservation = firstReservation))
        assertEquals(1, ledger.currentInputGeneration(target))

        val generationOneSubmit = SideEffectIdentityFactory.create(
            AgentAction.SubmitInput(nodeId = 1),
            screen,
            resolvedTarget = target,
            inputGeneration = ledger.currentInputGeneration(target),
        )!!
        assertFalse(ledger.check(generationOneSubmit).allowed)

        val secondAction = AgentAction.InputText("second", nodeId = 1, submit = false)
        val secondReservation = ledger.prepareInputGeneration(secondAction, target)
        val secondIdentity = SideEffectIdentityFactory.create(
            secondAction,
            screen,
            resolvedTarget = target,
            inputGeneration = secondReservation.generation,
        )!!
        assertTrue(ledger.markConfirmed(secondIdentity, inputGenerationReservation = secondReservation))
        assertEquals(2, ledger.currentInputGeneration(target))

        val generationTwoSubmit = SideEffectIdentityFactory.create(
            AgentAction.SubmitInput(nodeId = 1),
            screen,
            resolvedTarget = target,
            inputGeneration = ledger.currentInputGeneration(target),
        )!!
        assertTrue(ledger.check(generationTwoSubmit).allowed)
        assertTrue(ledger.markConfirmed(generationTwoSubmit))
        assertFalse(ledger.check(generationTwoSubmit).allowed)
        assertTrue(ledger.recordFor(generationOneSubmit) == null)
    }

    @Test
    fun failedReservationDoesNotAdvanceAndUnknownResultDoesAdvance() {
        val screen = inputScreen()
        val action = AgentAction.InputText("value", nodeId = 1)
        val target = TargetResolver.resolveActionTarget(action, screen).target!!
        val ledger = RunScopedSideEffectLedger("run-input")

        val failedReservation = ledger.prepareInputGeneration(action, target)
        val failedIdentity = SideEffectIdentityFactory.create(
            action,
            screen,
            resolvedTarget = target,
            inputGeneration = failedReservation.generation,
        )!!
        ledger.markFailed(failedIdentity)
        assertEquals(0, ledger.currentInputGeneration(target))

        val unknownReservation = ledger.prepareInputGeneration(action, target)
        val unknownIdentity = SideEffectIdentityFactory.create(
            action,
            screen,
            resolvedTarget = target,
            inputGeneration = unknownReservation.generation,
        )!!
        assertTrue(
            ledger.markUnknown(
                unknownIdentity,
                screen.observationId,
                inputGenerationReservation = unknownReservation,
            ),
        )
        assertEquals(1, ledger.currentInputGeneration(target))

        val replay = ledger.prepareInputGeneration(action, target)
        assertEquals(1, replay.generation)
        assertFalse(replay.advancesGeneration)
        val replayIdentity = SideEffectIdentityFactory.create(
            action,
            screen,
            resolvedTarget = target,
            inputGeneration = replay.generation,
        )!!
        assertFalse(ledger.check(replayIdentity).allowed)
    }

    @Test
    fun inputGenerationsAreIndependentPerEditableTargetAndClearWithRun() {
        val screen = inputScreen(twoFields = true)
        val firstAction = AgentAction.InputText("one", nodeId = 1)
        val secondAction = AgentAction.InputText("two", nodeId = 2)
        val firstTarget = TargetResolver.resolveActionTarget(firstAction, screen).target!!
        val secondTarget = TargetResolver.resolveActionTarget(secondAction, screen).target!!
        val ledger = RunScopedSideEffectLedger("run-input")

        val firstReservation = ledger.prepareInputGeneration(firstAction, firstTarget)
        val firstIdentity = SideEffectIdentityFactory.create(
            firstAction,
            screen,
            resolvedTarget = firstTarget,
            inputGeneration = firstReservation.generation,
        )!!
        assertTrue(ledger.markConfirmed(firstIdentity, inputGenerationReservation = firstReservation))
        assertEquals(1, ledger.currentInputGeneration(firstTarget))
        assertEquals(0, ledger.currentInputGeneration(secondTarget))

        val secondReservation = ledger.prepareInputGeneration(secondAction, secondTarget)
        val secondIdentity = SideEffectIdentityFactory.create(
            secondAction,
            screen,
            resolvedTarget = secondTarget,
            inputGeneration = secondReservation.generation,
        )!!
        assertTrue(ledger.markConfirmed(secondIdentity, inputGenerationReservation = secondReservation))
        assertEquals(1, ledger.currentInputGeneration(secondTarget))

        ledger.clear()
        assertEquals(0, ledger.currentInputGeneration(firstTarget))
        assertEquals(0, ledger.currentInputGeneration(secondTarget))
    }

    @Test
    fun runtimeCommitsGenerationOnlyAfterAndroidAcceptance() = runBlocking {
        val screen = inputScreen()
        val target = TargetResolver.resolveActionTarget(AgentAction.InputText("value", nodeId = 1), screen).target!!

        val failedLedger = RunScopedSideEffectLedger("run-failed")
        val failedSnapshots = PreDispatchEvidenceStore()
        val failedBindings = PredicateBindingStore()
        RuntimeStepEngine(InputDriver(executeSucceeds = false)).execute(
            request(screen, failedLedger, failedSnapshots, failedBindings),
        )
        assertEquals(0, failedLedger.currentInputGeneration(target))
        assertTrue(failedLedger.records().isEmpty())
        assertTrue(failedSnapshots.all().isEmpty())
        assertTrue(failedBindings.all().isEmpty())

        val unknownLedger = RunScopedSideEffectLedger("run-unknown")
        val unknownSnapshots = PreDispatchEvidenceStore()
        val unknownBindings = PredicateBindingStore()
        RuntimeStepEngine(InputDriver(executeSucceeds = true)).execute(
            request(screen, unknownLedger, unknownSnapshots, unknownBindings),
        )
        assertEquals(1, unknownLedger.currentInputGeneration(target))
        assertEquals(SideEffectResultState.UNKNOWN_SIDE_EFFECT, unknownLedger.records().single().state)
        assertTrue(unknownBindings.all().isNotEmpty())
    }

    private fun request(
        screen: Observation,
        sideEffects: RunScopedSideEffectLedger,
        snapshots: PreDispatchEvidenceStore,
        bindings: PredicateBindingStore,
    ): RuntimeStepRequest {
        val predicate = UiPredicate(
            UiPredicateKind.EDITABLE_EQUALS,
            predicateId = "input-p1",
            literal = "value",
            target = ElementSelector(packageName = "primary.app", viewIdResourceName = "primary:id/first"),
            description = "input contains value",
        )
        val milestone = TaskMilestone("input", "set input", listOf(predicate))
        val plan = TaskPlan("input", "primary.app", GoalContext("input"), listOf(milestone))
        return RuntimeStepRequest(
            step = 1,
            proposed = AgentAction.InputText("value", nodeId = 1, predicateId = "input-p1"),
            planningObservation = screen,
            executionObservation = screen,
            plan = plan,
            milestone = milestone,
            guard = ToolGuard(plan, PackagePolicy(mutableSetOf("primary.app"), "primary.app")),
            ledger = RunLedger(plan),
            bindings = bindings,
            recoveryPolicy = RecoveryPolicy(),
            packagePolicy = PackagePolicy(mutableSetOf("primary.app"), "primary.app"),
            launchablePackages = setOf("primary.app"),
            goal = plan.goal,
            targetPackage = "primary.app",
            evidenceCounters = StopGateEvidenceCounters(),
            runId = sideEffects.runId,
            sideEffects = sideEffects,
            preDispatchSnapshots = snapshots,
        )
    }

    private fun inputScreen(twoFields: Boolean = false): Observation {
        val first = UiNodeSnapshot(
            1,
            "",
            "",
            "android.widget.EditText",
            false,
            true,
            "0,0,200,50",
            viewId = "primary:id/first",
            treePath = listOf(0, 0),
            enabled = true,
            focused = true,
            packageName = "primary.app",
            windowId = 4,
        )
        val nodes = if (twoFields) listOf(
            first,
            first.copy(
                id = 2,
                bounds = "0,60,200,110",
                viewId = "primary:id/second",
                treePath = listOf(0, 1),
                focused = false,
            ),
        ) else listOf(first)
        return Observation(
            "primary.app",
            nodes,
            windowIds = setOf(4),
            windowPackages = mapOf(4 to "primary.app"),
        )
    }

    private class InputDriver(private val executeSucceeds: Boolean) : RuntimeStepDriver {
        override suspend fun executeDetailed(action: AgentAction, observation: Observation) =
            ActionExecutionResult(executeSucceeds, if (executeSucceeds) "accepted" else "text_set_failed")

        override suspend fun settle(before: Observation, action: AgentAction) =
            RuntimeStepSettleResult(DispatchResultState.RESULT_UNKNOWN, before, "timeout")

        override suspend fun executeRecovery(decision: RecoveryDecision, observation: Observation) =
            RuntimeStepRecoveryResult(false, observation, "stop")
    }
}
