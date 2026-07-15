package com.androidagent.app.agent

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResolvedActionTargetTest {
    @Test
    fun pointActivationIdentityUsesScreenshotAndNormalizedCoordinateRegion() {
        val screen = Observation("primary.app", emptyList())
        val first = SideEffectIdentityFactory.create(
            AgentAction.TapPoint(240, 240),
            screen,
            screenshotFingerprint = "screen-a",
        )
        val sameRegion = SideEffectIdentityFactory.create(
            AgentAction.TapPoint(241, 241),
            screen,
            screenshotFingerprint = "screen-a",
        )
        val differentScreenshot = SideEffectIdentityFactory.create(
            AgentAction.TapPoint(240, 240),
            screen,
            screenshotFingerprint = "screen-b",
        )
        val differentRegion = SideEffectIdentityFactory.create(
            AgentAction.TapPoint(264, 264),
            screen,
            screenshotFingerprint = "screen-a",
        )

        assertEquals(SideEffectFamily.POINT_ACTIVATION, first?.family)
        assertEquals(first, sameRegion)
        assertNotEquals(first, differentScreenshot)
        assertNotEquals(first, differentRegion)
    }

    @Test
    fun textChildAndClickableParentShareEffectiveSideEffectIdentity() {
        val screen = nestedClaimScreen()
        val textTarget = TargetResolver.resolveActionTarget(AgentAction.ClickText("领取"), screen).target!!
        val nodeTarget = TargetResolver.resolveActionTarget(AgentAction.ClickNode(1), screen).target!!

        assertEquals(2, textTarget.semanticNode.id)
        assertEquals(1, textTarget.effectiveActionNode.id)
        assertEquals(1, nodeTarget.semanticNode.id)
        assertEquals(1, nodeTarget.effectiveActionNode.id)
        assertEquals(ActionDispatchMode.ACCESSIBILITY_CLICK, textTarget.dispatchMode)

        val textIdentity = SideEffectIdentityFactory.create(
            AgentAction.ClickText("领取"),
            screen,
            resolvedTarget = textTarget,
        )!!
        val nodeIdentity = SideEffectIdentityFactory.create(
            AgentAction.ClickNode(1),
            screen,
            resolvedTarget = nodeTarget,
        )!!
        assertEquals(textIdentity, nodeIdentity)
        assertEquals(
            TargetResolver.crossWindowStructureKey(screen.nodes.first()),
            textIdentity.targetCrossWindowStructureKey,
        )
        assertNotEquals(
            TargetResolver.crossWindowStructureKey(screen.nodes.last()),
            textIdentity.targetCrossWindowStructureKey,
        )
    }

    @Test
    fun unknownTextChildBlocksParentNodeBeforeAndroidAndBindingStaysSemantic() = runBlocking {
        val screen = nestedClaimScreen()
        val firstMilestone = TaskMilestone(
            id = "m1",
            objective = "dismiss claim control",
            successPredicates = listOf(
                UiPredicate(
                    UiPredicateKind.ELEMENT_DISAPPEARED,
                    predicateId = "gone",
                    target = ElementSelector(
                        packageName = "primary.app",
                        viewIdResourceName = "primary:id/claim_label",
                        text = "领取",
                        className = "android.widget.TextView",
                    ),
                    description = "claim label disappears",
                ),
            ),
        )
        val firstPlan = plan(firstMilestone)
        val bindings = PredicateBindingStore()
        val sideEffects = RunScopedSideEffectLedger("run-1")
        val snapshots = PreDispatchEvidenceStore()
        val driver = UnknownDriver()

        RuntimeStepEngine(driver).execute(
            request(
                action = AgentAction.ClickText("领取", predicateId = "gone"),
                screen = screen,
                plan = firstPlan,
                milestone = firstMilestone,
                ledger = RunLedger(firstPlan),
                bindings = bindings,
                sideEffects = sideEffects,
                snapshots = snapshots,
            ),
        )

        assertEquals(1, driver.executeCount)
        val binding = bindings.get("m1", 0)
        assertNotNull(binding)
        assertEquals(listOf(0, 0), binding!!.identity.treePath)
        assertEquals("primary:id/claim_label", binding.identity.viewIdResourceName)
        val unknown = sideEffects.records().single()
        assertEquals(
            TargetResolver.crossWindowStructureKey(screen.nodes.first()),
            unknown.identity.targetCrossWindowStructureKey,
        )

        val repairMilestone = TaskMilestone(
            "repair",
            "verify safely",
            listOf(UiPredicate(UiPredicateKind.TEXT_PRESENT, predicateId = "repair-ready", literal = "Ready", description = "ready")),
        )
        val repairPlan = plan(repairMilestone)
        val second = RuntimeStepEngine(driver).execute(
            request(
                action = AgentAction.ClickNode(1),
                screen = screen,
                plan = repairPlan,
                milestone = repairMilestone,
                ledger = RunLedger(repairPlan),
                bindings = bindings,
                sideEffects = sideEffects,
                snapshots = snapshots,
            ),
        )

        assertEquals(1, driver.executeCount)
        assertFalse(second.status in setOf(RuntimeStepStatus.PROGRESS, RuntimeStepStatus.MILESTONE_COMPLETE))
        assertTrue(second.reason.contains("unknown side effect", ignoreCase = true))
    }

    @Test
    fun clickTextRequiresOneExactAccessibilityTarget() {
        val plan = plan(TaskMilestone(
            "m1",
            "inspect",
            listOf(UiPredicate(UiPredicateKind.TEXT_PRESENT, predicateId = "p1", literal = "Ready", description = "ready")),
        ))
        val guard = ToolGuard(plan, PackagePolicy(mutableSetOf("primary.app"), "primary.app"))

        val missing = guard.normalizeAndValidate(AgentAction.ClickText("领取"), Observation("primary.app", emptyList()))
        assertNull(missing.action)
        assertTrue(missing.rejection.orEmpty().contains("missing"))

        val partialScreen = Observation("primary.app", listOf(labelNode(2, "领取成功", listOf(0, 0))))
        val partial = guard.normalizeAndValidate(AgentAction.ClickText("领取"), partialScreen)
        assertNull(partial.action)
        assertTrue(partial.rejection.orEmpty().contains("missing"))

        val duplicateScreen = Observation(
            "primary.app",
            listOf(
                labelNode(2, "领取", listOf(0, 0)),
                labelNode(3, "领取", listOf(1, 0)),
            ),
        )
        val duplicate = guard.normalizeAndValidate(AgentAction.ClickText("领取"), duplicateScreen)
        assertNull(duplicate.action)
        assertTrue(duplicate.rejection.orEmpty().contains("ambiguous"))
    }

    @Test
    fun clickableParentLookupStopsAfterFourLevels() {
        val parent = labelNode(1, "", listOf(0)).copy(
            className = "android.widget.LinearLayout",
            clickable = true,
            viewId = "primary:id/container",
        )
        val fourLevels = labelNode(2, "Four", listOf(0, 0, 0, 0, 0))
        val allowed = Observation("primary.app", listOf(parent, fourLevels))
        assertEquals(1, TargetResolver.resolveActionTarget(AgentAction.ClickText("Four"), allowed).target?.effectiveActionNode?.id)

        val fiveLevels = labelNode(3, "Five", listOf(0, 0, 0, 0, 0, 0))
        val rejected = TargetResolver.resolveActionTarget(AgentAction.ClickText("Five"), Observation("primary.app", listOf(parent, fiveLevels)))
        assertNull(rejected.target)
        assertEquals(ActionTargetFailure.NOT_ACTIONABLE, rejected.failure)
    }

    @Test
    fun mutatingClickWithoutIdentityNeverReachesDriverAndOcrIsNotADispatchMode() = runBlocking {
        val milestone = TaskMilestone(
            "m1",
            "verify",
            listOf(UiPredicate(UiPredicateKind.TEXT_PRESENT, predicateId = "p1", literal = "Ready", description = "ready")),
        )
        val plan = plan(milestone)
        val screen = Observation("primary.app", emptyList())
        val driver = UnknownDriver()
        val result = RuntimeStepEngine(driver).execute(
            request(
                action = AgentAction.ClickText("not present"),
                screen = screen,
                plan = plan,
                milestone = milestone,
                ledger = RunLedger(plan),
                bindings = PredicateBindingStore(),
                sideEffects = RunScopedSideEffectLedger("run-2"),
                snapshots = PreDispatchEvidenceStore(),
            ),
        )
        assertEquals(0, driver.executeCount)
        assertFalse(result.events.any { it.phase == "execute" })
        assertNull(SideEffectIdentityFactory.create(AgentAction.ClickText("not present"), screen))
        assertFalse(ActionDispatchMode.values().any { it.name.contains("OCR") })
    }

    private fun request(
        action: AgentAction,
        screen: Observation,
        plan: TaskPlan,
        milestone: TaskMilestone,
        ledger: RunLedger,
        bindings: PredicateBindingStore,
        sideEffects: RunScopedSideEffectLedger,
        snapshots: PreDispatchEvidenceStore,
    ) = RuntimeStepRequest(
        step = 1,
        proposed = action,
        planningObservation = screen,
        executionObservation = screen,
        plan = plan,
        milestone = milestone,
        guard = ToolGuard(plan, PackagePolicy(mutableSetOf("primary.app"), "primary.app")),
        ledger = ledger,
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

    private fun nestedClaimScreen(): Observation {
        val parent = UiNodeSnapshot(
            id = 1,
            text = "",
            description = "",
            className = "android.widget.LinearLayout",
            clickable = true,
            editable = false,
            bounds = "0,0,200,80",
            viewId = "primary:id/claim_container",
            treePath = listOf(0),
            packageName = "primary.app",
            windowId = 7,
        )
        val child = labelNode(2, "领取", listOf(0, 0)).copy(windowId = 7)
        return Observation(
            packageName = "primary.app",
            nodes = listOf(parent, child),
            windowIds = setOf(7),
            windowPackages = mapOf(7 to "primary.app"),
        )
    }

    private fun labelNode(id: Int, text: String, path: List<Int>) = UiNodeSnapshot(
        id = id,
        text = text,
        description = "",
        className = "android.widget.TextView",
        clickable = false,
        editable = false,
        bounds = "20,20,160,60",
        viewId = "primary:id/claim_label",
        treePath = path,
        packageName = "primary.app",
        windowId = 7,
    )

    private fun plan(milestone: TaskMilestone) = TaskPlan(
        summary = "target contract",
        targetAppHint = "primary.app",
        goal = GoalContext("target contract"),
        milestones = listOf(milestone),
    )

    private class UnknownDriver : RuntimeStepDriver {
        var executeCount = 0

        override suspend fun executeDetailed(action: AgentAction, observation: Observation): ActionExecutionResult {
            executeCount++
            return ActionExecutionResult(true, "accepted")
        }

        override suspend fun settle(before: Observation, action: AgentAction) =
            RuntimeStepSettleResult(DispatchResultState.RESULT_UNKNOWN, before, "timeout")

        override suspend fun executeRecovery(decision: RecoveryDecision, observation: Observation) =
            RuntimeStepRecoveryResult(false, observation, "stop")
    }
}
