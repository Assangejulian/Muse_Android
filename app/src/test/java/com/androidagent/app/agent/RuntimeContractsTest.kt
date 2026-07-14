package com.androidagent.app.agent

import com.androidagent.app.accessibility.AgentAccessibilityService
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeContractsTest {
    private val plan = TaskPlan(
        summary = "state contract",
        targetAppHint = "primary.app",
        goal = GoalContext("state contract"),
        milestones = listOf(
            TaskMilestone(
                "verify",
                "verify target",
                listOf(UiPredicate(UiPredicateKind.TOGGLE_ON, target = ElementSelector(text = "Target", className = "Switch"), description = "target is on")),
            ),
        ),
    )

    @Test
    fun anotherEnabledSwitchCannotProveTargetToggle() {
        val screen = Observation(
            "primary.app",
            listOf(
                UiNodeSnapshot(1, "Target", "", "Switch", true, false, "0,0,100,30", checked = false, packageName = "primary.app"),
                UiNodeSnapshot(2, "Other", "", "Switch", true, false, "0,40,100,70", checked = true, packageName = "primary.app"),
            ),
        )
        assertFalse(MilestoneEvaluator.evaluate(plan.milestones.single(), plan, screen, "primary.app").proven)
    }

    @Test
    fun duplicateTargetElementsRemainUnknown() {
        val screen = Observation(
            "primary.app",
            listOf(
                UiNodeSnapshot(1, "Target", "", "Switch", true, false, "0,0,100,30", checked = true, packageName = "primary.app"),
                UiNodeSnapshot(2, "Target", "", "Switch", true, false, "0,40,100,70", checked = true, packageName = "primary.app"),
            ),
        )
        assertFalse(MilestoneEvaluator.evaluate(plan.milestones.single(), plan, screen, "primary.app").proven)
    }

    @Test
    fun packagePredicateUsesItsOwnTargetPackage() {
        val milestone = TaskMilestone(
            "external",
            "external app",
            listOf(UiPredicate(UiPredicateKind.PACKAGE_FOREGROUND, targetPackage = "secondary.app", description = "secondary foreground")),
        )
        val screen = Observation("secondary.app", emptyList())
        assertTrue(MilestoneEvaluator.evaluate(milestone, plan, screen, "primary.app").proven)
    }

    @Test
    fun semanticOnlyPlansAreRejected() {
        val invalid = TaskPlan(
            summary = "invalid",
            targetAppHint = "primary.app",
            goal = GoalContext("invalid"),
            milestones = listOf(TaskMilestone("m1", "claim", listOf(UiPredicate(UiPredicateKind.SEMANTIC_CLAIM, description = "claim")))),
        )
        assertFalse(runCatching { TaskPlanValidator.requireValid(invalid) }.isSuccess)
    }

    @Test
    fun semanticClaimIsAuxiliaryWhenHardPredicateIsProven() {
        val milestone = TaskMilestone(
            "mixed",
            "show text",
            listOf(
                UiPredicate(UiPredicateKind.TEXT_PRESENT, literal = "ready", description = "ready is visible"),
                UiPredicate(UiPredicateKind.SEMANTIC_CLAIM, description = "model agrees"),
            ),
        )
        val mixedPlan = plan.copy(milestones = listOf(milestone))
        val screen = Observation("primary.app", listOf(UiNodeSnapshot(1, "ready", "", "TextView", false, false, "0,0,100,30", packageName = "primary.app")))
        assertTrue(MilestoneEvaluator.evaluate(milestone, mixedPlan, screen, "primary.app").proven)
    }

    @Test
    fun unboundTargetCannotProveBeforeActorBinding() {
        val targetMilestone = TaskMilestone(
            "toggle",
            "toggle target",
            listOf(UiPredicate(UiPredicateKind.TOGGLE_STATE, expectedChecked = true, targetHint = "Target", description = "target is checked")),
        )
        val targetPlan = plan.copy(milestones = listOf(targetMilestone))
        val screen = Observation("primary.app", listOf(
            UiNodeSnapshot(1, "Target", "", "Switch", true, false, "0,0,100,30", checked = true, packageName = "primary.app"),
        ))
        assertFalse(MilestoneEvaluator.evaluate(targetMilestone, targetPlan, screen, "primary.app").proven)

        val bindings = PredicateBindingStore()
        assertTrue(bindings.bindAction(targetMilestone, AgentAction.EnsureToggle(1, true), screen).bound)
        assertTrue(MilestoneEvaluator.evaluate(targetMilestone, targetPlan, screen, "primary.app", bindings).proven)
    }

    @Test
    fun typedToggleSupportsOffStateAndDoesNotScanOtherSwitches() {
        val milestone = TaskMilestone(
            "toggle",
            "toggle target off",
            listOf(UiPredicate(UiPredicateKind.TOGGLE_STATE, expectedChecked = false, target = ElementSelector(text = "Target", className = "Switch"), description = "target is off")),
        )
        val targetPlan = plan.copy(milestones = listOf(milestone))
        val screen = Observation("primary.app", listOf(
            UiNodeSnapshot(1, "Target", "", "Switch", true, false, "0,0,100,30", checked = false, packageName = "primary.app"),
            UiNodeSnapshot(2, "Other", "", "Switch", true, false, "0,40,100,70", checked = true, packageName = "primary.app"),
        ))
        val bindings = PredicateBindingStore()
        assertTrue(bindings.bind(milestone, 0, screen.nodes.first(), screen).bound)
        assertTrue(MilestoneEvaluator.evaluate(milestone, targetPlan, screen, "primary.app", bindings).proven)
    }

    @Test
    fun disappearedPredicateRequiresAConcretePreActionBinding() {
        val milestone = TaskMilestone(
            "dismiss",
            "dismiss target",
            listOf(UiPredicate(UiPredicateKind.ELEMENT_DISAPPEARED, targetHint = "Dismiss", description = "dismiss target disappeared")),
        )
        val plan = this.plan.copy(milestones = listOf(milestone))
        val before = Observation("primary.app", listOf(UiNodeSnapshot(1, "Dismiss", "", "Button", true, false, "0,0,100,30", packageName = "primary.app")))
        val after = Observation("primary.app", emptyList())
        val bindings = PredicateBindingStore()
        assertFalse(MilestoneEvaluator.evaluate(milestone, plan, after, "primary.app", bindings).proven)
        assertTrue(bindings.bindAction(milestone, AgentAction.ClickText("Dismiss"), before).bound)
        assertTrue(MilestoneEvaluator.evaluate(milestone, plan, after, "primary.app", bindings).proven)
    }

    @Test
    fun parserNormalizesLegacyToggleOnAndRejectsFuzzyElementState() {
        val legacy = """{"summary":"toggle","milestones":[{"id":"m1","objective":"toggle","successPredicates":[{"kind":"TOGGLE_ON","targetHint":"switch","description":"on"}]}]}"""
        val parsed = TaskPlanParser.parse(legacy, GoalContext("toggle"))
        assertEquals(UiPredicateKind.TOGGLE_STATE, parsed.milestones.single().successPredicates.single().kind)
        assertEquals(true, parsed.milestones.single().successPredicates.single().expectedChecked)
        val fuzzy = """{"summary":"state","milestones":[{"id":"m1","objective":"state","successPredicates":[{"kind":"ELEMENT_STATE","targetHint":"switch","literal":"on","description":"state"}]}]}"""
        assertFalse(runCatching { TaskPlanParser.parse(fuzzy, GoalContext("state")) }.isSuccess)
    }

    @Test
    fun textPresentDoesNotRequireEnabledFlag() {
        val milestone = TaskMilestone(
            "text",
            "show text",
            listOf(UiPredicate(UiPredicateKind.TEXT_PRESENT, literal = "visible", description = "visible text")),
        )
        val plan = this.plan.copy(milestones = listOf(milestone))
        val screen = Observation("primary.app", listOf(UiNodeSnapshot(1, "visible", "", "TextView", false, false, "0,0,100,30", enabled = false, packageName = "primary.app")))
        assertTrue(MilestoneEvaluator.evaluate(milestone, plan, screen, "primary.app").proven)
    }

    @Test
    fun replanRetainsValidBindingForAnIncompleteMilestone() {
        val predicate = UiPredicate(
            UiPredicateKind.ELEMENT_PRESENT,
            targetHint = "Target",
            description = "target remains present",
        )
        val previous = plan.copy(
            milestones = listOf(
                TaskMilestone("m1", "done", listOf(UiPredicate(UiPredicateKind.TEXT_PRESENT, literal = "done", description = "done"))),
                TaskMilestone("m2", "pending", listOf(predicate)),
            ),
        )
        val revised = previous.copy(
            milestones = previous.milestones + TaskMilestone(
                "m3",
                "new pending",
                listOf(UiPredicate(UiPredicateKind.TEXT_PRESENT, literal = "new", description = "new")),
            ),
        )
        val screen = Observation(
            "primary.app",
            listOf(UiNodeSnapshot(1, "Target", "", "Button", true, false, "0,0,100,30", packageName = "primary.app")),
        )
        val bindings = PredicateBindingStore()
        assertTrue(bindings.bind(previous.milestones[1], 0, screen.nodes.single(), screen).bound)
        bindings.retainCompleted(previous, revised, setOf("m1"))
        assertTrue(bindings.get("m2", 0) != null)
    }

    @Test
    fun predicateBindingPrepareIsTransactionalAndRollbackLeavesNoProof() {
        val milestone = TaskMilestone(
            "dismiss",
            "dismiss target",
            listOf(UiPredicate(UiPredicateKind.ELEMENT_DISAPPEARED, targetHint = "Dismiss", description = "target disappears")),
        )
        val before = Observation("primary.app", listOf(
            UiNodeSnapshot(4, "Dismiss", "", "Button", true, false, "0,0,100,30", packageName = "primary.app"),
        ))
        val bindings = PredicateBindingStore()
        val prepared = bindings.prepareActionBinding(milestone, AgentAction.ClickNode(4), before, runId = "run-1")
        assertTrue(prepared.prepared)
        assertTrue(prepared.provisional.isNotEmpty())
        assertEquals(null, bindings.get("dismiss", 0))

        bindings.rollbackAll(prepared.provisional)
        assertEquals(null, bindings.get("dismiss", 0))

        val committed = bindings.prepareActionBinding(milestone, AgentAction.ClickNode(4), before, runId = "run-1")
        assertTrue(bindings.commitAll(committed.provisional))
        assertTrue(MilestoneEvaluator.evaluate(milestone, plan, Observation("primary.app", emptyList()), "primary.app", bindings, runId = "run-1").proven)
    }

    @Test
    fun changedTextOnStableElementIsNotDisappearance() {
        val milestone = TaskMilestone(
            "dismiss",
            "dismiss target",
            listOf(UiPredicate(UiPredicateKind.ELEMENT_DISAPPEARED, description = "target disappears")),
        )
        val before = Observation("primary.app", listOf(
            UiNodeSnapshot(4, "Before", "", "Button", true, false, "0,0,100,30", viewId = "primary:id/action", packageName = "primary.app"),
        ))
        val after = Observation("primary.app", listOf(
            UiNodeSnapshot(9, "After", "", "Button", true, false, "2,2,102,32", viewId = "primary:id/action", packageName = "primary.app"),
        ))
        val bindings = PredicateBindingStore()
        assertTrue(bindings.bind(milestone, 0, before.nodes.single(), before, "run-1").bound)
        assertFalse(MilestoneEvaluator.evaluate(milestone, plan, after, "primary.app", bindings, runId = "run-1").proven)
    }

    @Test
    fun observationOnlyBindingCommitsAndEvaluatesWithoutAnExecutableSideEffect() {
        val milestone = TaskMilestone(
            "state",
            "inspect target",
            listOf(UiPredicate(UiPredicateKind.ELEMENT_PRESENT, predicateId = "state-p1", targetHint = "Target", description = "target present")),
        )
        val screen = Observation("primary.app", listOf(
            UiNodeSnapshot(1, "Target", "", "Button", true, false, "0,0,100,30", packageName = "primary.app"),
        ))
        val bindings = PredicateBindingStore()
        val prepared = bindings.prepareActionBinding(
            milestone,
            AgentAction.BindPredicate("state-p1", selector = ElementSelector(text = "Target", className = "Button")),
            screen,
            "run-1",
        )
        assertTrue(prepared.prepared)
        assertTrue(bindings.commitAll(prepared.provisional))
        assertTrue(MilestoneEvaluator.evaluate(milestone, plan, screen, "primary.app", bindings, runId = "run-1").proven)
    }

    @Test
    fun compactPlanTextExposesPredicateContractsAndBindingLifecycle() {
        val milestone = TaskMilestone(
            "m1",
            "inspect target",
            listOf(UiPredicate(UiPredicateKind.ELEMENT_PRESENT, targetHint = "Target", description = "target present")),
        )
        val compact = plan.copy(milestones = listOf(milestone)).let { it.compactText(0) }
        assertTrue(compact.contains("predicateId=m1-p1"))
        assertTrue(compact.contains("kind=ELEMENT_PRESENT"))
        assertTrue(compact.contains("targetHint=Target"))
        assertTrue(compact.contains("binding=UNBOUND"))

        val screen = Observation("primary.app", listOf(UiNodeSnapshot(1, "Target", "", "Button", true, false, "0,0,100,30", packageName = "primary.app")))
        val bindings = PredicateBindingStore()
        val prepared = bindings.prepareBinding(milestone, 0, screen.nodes.single(), screen, "run-1")
        assertEquals(BindingLifecycle.PREPARED, prepared.provisional?.binding?.lifecycle)
        val provisional = listOfNotNull(prepared.provisional)
        assertTrue(bindings.markDispatched(provisional))
        assertTrue(bindings.commitDispatched(provisional))
        assertEquals(BindingLifecycle.COMMITTED, bindings.get("m1", 0)?.lifecycle)
        assertTrue(compact.isNotBlank())
        assertTrue(milestone.successPredicates.single().predicateId == null)
    }

    @Test
    fun compactPlanTextDoesNotEchoRawSelectorValues() {
        val selector = ElementSelector(text = "private label", viewIdResourceName = "private:id/field", className = "EditText")
        val milestone = TaskMilestone(
            "m1",
            "inspect target",
            listOf(UiPredicate(UiPredicateKind.ELEMENT_PRESENT, target = selector, description = "target present")),
        )
        val compact = plan.copy(milestones = listOf(milestone)).compactText(0)
        assertTrue(compact.contains("selector_present=true"))
        assertFalse(compact.contains("private label"))
        assertFalse(compact.contains("private:id/field"))
    }

    @Test
    fun duplicateAndUnsafePredicateIdsAreRejected() {
        val duplicate = plan.copy(milestones = listOf(
            TaskMilestone("m1", "one", listOf(UiPredicate(UiPredicateKind.TEXT_PRESENT, literal = "one", predicateId = "same", description = "one"))),
            TaskMilestone("m2", "two", listOf(UiPredicate(UiPredicateKind.TEXT_PRESENT, literal = "two", predicateId = "same", description = "two"))),
        ))
        assertFalse(runCatching { TaskPlanValidator.requireValid(duplicate) }.isSuccess)
        val unsafe = plan.copy(milestones = listOf(
            TaskMilestone("m1", "one", listOf(UiPredicate(UiPredicateKind.TEXT_PRESENT, literal = "one", predicateId = "bad.id", description = "one"))),
        ))
        assertFalse(runCatching { TaskPlanValidator.requireValid(unsafe) }.isSuccess)
    }

    @Test
    fun replanCannotReusePredicateIdForDifferentKind() {
        val previous = plan.copy(milestones = listOf(TaskMilestone(
            "m1",
            "state",
            listOf(UiPredicate(UiPredicateKind.ELEMENT_PRESENT, predicateId = "same", targetHint = "Target", description = "present")),
        )))
        val revised = previous.copy(milestones = listOf(TaskMilestone(
            "m1",
            "state",
            listOf(UiPredicate(UiPredicateKind.ELEMENT_DISAPPEARED, predicateId = "same", targetHint = "Target", description = "gone")),
        )))
        assertFalse(runCatching { TaskPlanValidator.requireCompatiblePredicateIds(previous, revised) }.isSuccess)
    }

    @Test
    fun reorderedPredicatesRetainBindingByStableIdAndMeaning() {
        val first = TaskMilestone(
            "m1",
            "state",
            listOf(
                UiPredicate(UiPredicateKind.TEXT_PRESENT, literal = "done", predicateId = "done", description = "done"),
                UiPredicate(UiPredicateKind.ELEMENT_PRESENT, targetHint = "Target", predicateId = "target", description = "target"),
            ),
        )
        val previous = plan.copy(milestones = listOf(first))
        val revised = previous.copy(milestones = listOf(first.copy(successPredicates = first.successPredicates.reversed())))
        val screen = Observation("primary.app", listOf(UiNodeSnapshot(4, "Target", "", "Button", true, false, "0,0,100,30", packageName = "primary.app")))
        val bindings = PredicateBindingStore()
        assertTrue(bindings.bind(first, 1, screen.nodes.single(), screen, "run-1").bound)
        bindings.retainCompleted(previous, revised, emptySet())
        assertEquals(0, bindings.get("m1", 0)?.predicateIndex)
        assertEquals("target", bindings.get("m1", 0)?.predicateId)
    }

    @Test
    fun normalizedTargetHintKeepsCompatibleBindingAcrossReplan() {
        val previousPredicate = UiPredicate(
            UiPredicateKind.ELEMENT_PRESENT,
            targetHint = " Notification-toggle ",
            predicateId = "target",
            description = "target",
        )
        val revisedPredicate = previousPredicate.copy(targetHint = "notification toggle")
        val previous = plan.copy(milestones = listOf(TaskMilestone("m1", "state", listOf(previousPredicate))))
        val revised = plan.copy(milestones = listOf(TaskMilestone("m1", "state", listOf(revisedPredicate))))
        val screen = Observation("primary.app", listOf(UiNodeSnapshot(1, "Notification-toggle", "", "Button", true, false, "0,0,100,30", packageName = "primary.app")))
        val bindings = PredicateBindingStore()
        assertTrue(bindings.bind(previous.milestones.single(), 0, screen.nodes.single(), screen, "run-1").bound)
        bindings.retainCompleted(previous, revised, emptySet())
        assertEquals("target", bindings.get("m1", 0)?.predicateId)
    }

    @Test
    fun alreadySatisfiedStatePredicateCanProveWithoutMutationWhenSelectorIsConcrete() {
        val milestone = TaskMilestone(
            "m1",
            "toggle",
            listOf(UiPredicate(UiPredicateKind.TOGGLE_STATE, expectedChecked = true, target = ElementSelector(text = "Target", className = "Switch"), description = "target on")),
        )
        val checked = Observation("primary.app", listOf(UiNodeSnapshot(1, "Target", "", "Switch", true, false, "0,0,100,30", checked = true, packageName = "primary.app")))
        val evidence = MilestoneEvaluator.evaluate(milestone, plan, checked, "primary.app")
        assertTrue(evidence.proven)
        val counters = StopGateEvidenceCounters(deterministicEvidenceCount = 1, verifiedMilestones = 1)
        assertTrue(counters.hasLocalEvidence())
        assertEquals(0, counters.successfulMutatingActions)
    }

    @Test
    fun targetHintMatcherAllowsAbstractControlDescriptions() {
        val node = UiNodeSnapshot(1, "Notifications", "", "android.widget.Switch", true, false, "0,0,100,30")
        assertEquals(TargetHintResult.MATCH, TargetHintMatcher.match("notification settings toggle", node))
    }

    @Test
    fun identityResolutionDistinguishesMissingPackageAndWindowContexts() {
        val original = UiNodeSnapshot(1, "Target", "", "Switch", true, false, "0,0,100,30", viewId = "primary:id/switch", packageName = "primary.app", windowId = 3)
        val identity = BoundElementIdentity.from(original)
        assertEquals(IdentityResolution.MissingInSameWindow, NodeSelector.resolveIdentity(Observation("primary.app", listOf(UiNodeSnapshot(2, "Other", "", "Switch", true, false, "0,40,100,70", viewId = "primary:id/other", packageName = "primary.app", windowId = 3))), identity))
        assertEquals(IdentityResolution.PackageChanged, NodeSelector.resolveIdentity(Observation("secondary.app", emptyList()), identity))
        assertEquals(IdentityResolution.WindowRecreated, NodeSelector.resolveIdentity(Observation("primary.app", listOf(original.copy(windowId = 4))), identity))
    }

    @Test
    fun samePackageWindowWithDifferentStructureIsBoundWindowGone() {
        val original = UiNodeSnapshot(
            1,
            "Dismiss",
            "",
            "Button",
            true,
            false,
            "0,0,100,30",
            viewId = "primary:id/dismiss",
            treePath = listOf(0, 1),
            packageName = "primary.app",
            windowId = 10,
        )
        val different = UiNodeSnapshot(
            2,
            "Dashboard",
            "",
            "TextView",
            false,
            false,
            "0,0,300,80",
            viewId = "primary:id/dashboard",
            treePath = listOf(0, 0),
            packageName = "primary.app",
            windowId = 11,
        )
        val after = Observation(
            "primary.app",
            listOf(different),
            windowIds = setOf(11),
            windowPackages = mapOf(11 to "primary.app"),
        )
        assertEquals(IdentityResolution.BoundWindowGone, NodeSelector.resolveIdentity(after, BoundElementIdentity.from(original)))
    }

    @Test
    fun nonEmptyWindowIdentityCanProveSameWindowDisappearance() {
        val milestone = TaskMilestone(
            "dismiss",
            "dismiss target",
            listOf(UiPredicate(UiPredicateKind.ELEMENT_DISAPPEARED, predicateId = "dismiss-p1", targetHint = "Dismiss", description = "dismissed")),
        )
        val beforeNode = UiNodeSnapshot(
            1, "Dismiss", "", "Button", true, false, "0,0,100,30",
            viewId = "primary:id/dismiss", packageName = "primary.app", windowId = 10,
        )
        val before = Observation("primary.app", listOf(beforeNode), windowIds = setOf(10))
        val after = Observation(
            "primary.app",
            listOf(UiNodeSnapshot(2, "Other", "", "TextView", false, false, "0,40,100,70", packageName = "primary.app", windowId = 10)),
            windowIds = setOf(10),
        )
        val bindings = PredicateBindingStore()
        assertTrue(bindings.bind(milestone, 0, beforeNode, before, "run-1").bound)
        assertEquals(IdentityResolution.MissingInSameWindow, NodeSelector.resolveIdentity(after, BoundElementIdentity.from(beforeNode)))
        assertTrue(MilestoneEvaluator.evaluate(milestone, plan, after, "primary.app", bindings, runId = "run-1").proven)
    }

    @Test
    fun boundWindowGoneNeedsPositivePostcondition() {
        val disappeared = UiPredicate(UiPredicateKind.ELEMENT_DISAPPEARED, predicateId = "dismiss-p1", targetHint = "Dismiss", description = "dialog dismissed")
        val positive = UiPredicate(UiPredicateKind.TEXT_PRESENT, predicateId = "dismiss-p2", literal = "Ready", description = "underlying page is ready")
        val milestone = TaskMilestone("dismiss", "dismiss dialog", listOf(disappeared, positive))
        val targetPlan = plan.copy(milestones = listOf(milestone))
        val dialog = UiNodeSnapshot(1, "Dismiss", "", "Button", true, false, "0,0,100,30", viewId = "primary:id/dismiss", packageName = "primary.app", windowId = 10)
        val before = Observation("primary.app", listOf(dialog), windowIds = setOf(10))
        val bindings = PredicateBindingStore()
        val ledger = RunLedger(targetPlan)
        val action = AgentAction.ClickNode(1, predicateId = "dismiss-p1")
        val prepared = bindings.prepareActionBinding(milestone, action, before, "run-1")
        val actionKey = ledger.actionKey(action, before)
        val baseline = MilestoneEvaluator.strongPostconditionBaseline(milestone, targetPlan, before, "primary.app", bindings, "run-1")
        assertTrue(bindings.commitMutation(prepared, actionKey, before.observationId, baseline))

        val underlying = Observation(
            "primary.app",
            listOf(UiNodeSnapshot(2, "Ready", "", "TextView", false, false, "0,0,100,30", packageName = "primary.app", windowId = 11)),
            windowIds = setOf(11),
        )
        assertTrue(bindings.markResultObserved(prepared, actionKey))
        assertEquals(IdentityResolution.BoundWindowGone, NodeSelector.resolveIdentity(underlying, BoundElementIdentity.from(dialog)))
        assertTrue(MilestoneEvaluator.evaluate(milestone, targetPlan, underlying, "primary.app", bindings, runId = "run-1").proven)

        val noPostcondition = milestone.copy(successPredicates = listOf(disappeared))
        val noPostPlan = plan.copy(milestones = listOf(noPostcondition))
        val noPostBindings = PredicateBindingStore()
        val noPostLedger = RunLedger(noPostPlan)
        val noPostAction = AgentAction.ClickNode(1, predicateId = "dismiss-p1")
        val noPostPrepared = noPostBindings.prepareActionBinding(noPostcondition, noPostAction, before, "run-2")
        val noPostActionKey = noPostLedger.actionKey(noPostAction, before)
        assertTrue(noPostBindings.commitMutation(noPostPrepared, noPostActionKey, before.observationId, emptyMap()))
        assertTrue(noPostBindings.markResultObserved(noPostPrepared, noPostActionKey))
        val noPostEvidence = MilestoneEvaluator.evaluate(noPostcondition, noPostPlan, underlying, "primary.app", noPostBindings, runId = "run-2")
        assertFalse(noPostEvidence.proven)
        assertTrue(noPostEvidence.details.any { it.contains("no new strong postcondition") })
    }

    @Test
    fun observationOnlyBindingCannotProveBoundWindowGone() {
        val milestone = TaskMilestone(
            "dismiss",
            "dismiss dialog",
            listOf(
                UiPredicate(UiPredicateKind.ELEMENT_DISAPPEARED, predicateId = "dismiss-gone", targetHint = "Dismiss", description = "gone"),
                UiPredicate(UiPredicateKind.TEXT_PRESENT, predicateId = "dismiss-ready", literal = "Ready", description = "ready"),
            ),
        )
        val targetPlan = plan.copy(milestones = listOf(milestone))
        val dialog = UiNodeSnapshot(1, "Dismiss", "", "Button", true, false, "0,0,100,30", viewId = "primary:id/dismiss", packageName = "primary.app", windowId = 7)
        val before = Observation("primary.app", listOf(dialog), windowIds = setOf(7))
        val after = Observation(
            "primary.app",
            listOf(UiNodeSnapshot(2, "Ready", "", "TextView", false, false, "0,0,100,30", packageName = "primary.app", windowId = 3)),
            windowIds = setOf(3),
        )
        val bindings = PredicateBindingStore()
        val prepared = bindings.prepareActionBinding(
            milestone,
            AgentAction.BindPredicate("dismiss-gone", nodeId = 1),
            before,
            "run-observe",
        )
        assertTrue(bindings.commitObservation(prepared, BindingOrigin.OBSERVATION_ONLY))
        assertEquals(BindingOrigin.OBSERVATION_ONLY, bindings.get("dismiss", 0)?.origin)
        assertFalse(MilestoneEvaluator.evaluate(milestone, targetPlan, after, "primary.app", bindings, runId = "run-observe").proven)
    }

    @Test
    fun weakPredicatesThatWereAlreadyTrueCannotUnlockBoundWindowGone() {
        fun evaluateWith(weak: UiPredicate, imeVisible: Boolean): PredicateEvidence {
            val disappeared = UiPredicate(UiPredicateKind.ELEMENT_DISAPPEARED, predicateId = "gone", targetHint = "Dismiss", description = "gone")
            val milestone = TaskMilestone("dismiss", "dismiss", listOf(disappeared, weak))
            val targetPlan = plan.copy(milestones = listOf(milestone))
            val dialog = UiNodeSnapshot(1, "Dismiss", "", "Button", true, false, "0,0,100,30", viewId = "primary:id/dismiss", packageName = "primary.app", windowId = 7)
            val before = Observation("primary.app", listOf(dialog), imeVisible = imeVisible, windowIds = setOf(7))
            val after = Observation("primary.app", emptyList(), imeVisible = imeVisible, windowIds = setOf(3), windowPackages = mapOf(3 to "primary.app"))
            val bindings = PredicateBindingStore()
            val ledger = RunLedger(targetPlan)
            val action = AgentAction.ClickNode(1, predicateId = "gone")
            val prepared = bindings.prepareActionBinding(milestone, action, before, "run-weak")
            val actionKey = ledger.actionKey(action, before)
            val baseline = MilestoneEvaluator.strongPostconditionBaseline(milestone, targetPlan, before, "primary.app", bindings, "run-weak")
            assertTrue(bindings.commitMutation(prepared, actionKey, before.observationId, baseline))
            assertTrue(bindings.markResultObserved(prepared, actionKey))
            return MilestoneEvaluator.evaluate(milestone, targetPlan, after, "primary.app", bindings, runId = "run-weak")
        }

        val packageEvidence = evaluateWith(
            UiPredicate(UiPredicateKind.PACKAGE_FOREGROUND, predicateId = "package", targetPackage = "primary.app", description = "package"),
            imeVisible = false,
        )
        val imeEvidence = evaluateWith(
            UiPredicate(UiPredicateKind.IME_HIDDEN, predicateId = "ime", description = "ime hidden"),
            imeVisible = false,
        )
        assertFalse(packageEvidence.proven)
        assertFalse(imeEvidence.proven)
        assertTrue(packageEvidence.details.any { it.contains("no new strong postcondition") })
        assertTrue(imeEvidence.details.any { it.contains("no new strong postcondition") })
    }

    @Test
    fun newlyAppearingUniqueControlCanUnlockBoundWindowGone() {
        val readySelector = ElementSelector(
            packageName = "primary.app",
            viewIdResourceName = "primary:id/ready",
            className = "TextView",
        )
        val milestone = TaskMilestone(
            "dismiss",
            "dismiss",
            listOf(
                UiPredicate(UiPredicateKind.ELEMENT_DISAPPEARED, predicateId = "gone", targetHint = "Dismiss", description = "gone"),
                UiPredicate(UiPredicateKind.ELEMENT_PRESENT, predicateId = "ready", target = readySelector, description = "ready control"),
            ),
        )
        val targetPlan = plan.copy(milestones = listOf(milestone))
        val dialog = UiNodeSnapshot(1, "Dismiss", "", "Button", true, false, "0,0,100,30", viewId = "primary:id/dismiss", packageName = "primary.app", windowId = 7)
        val before = Observation("primary.app", listOf(dialog), windowIds = setOf(7))
        val ready = UiNodeSnapshot(2, "Ready", "", "TextView", false, false, "0,0,100,30", viewId = "primary:id/ready", packageName = "primary.app", windowId = 3)
        val after = Observation("primary.app", listOf(ready), windowIds = setOf(3))
        val bindings = PredicateBindingStore()
        val ledger = RunLedger(targetPlan)
        val action = AgentAction.ClickNode(1, predicateId = "gone")
        val prepared = bindings.prepareActionBinding(milestone, action, before, "run-control")
        val actionKey = ledger.actionKey(action, before)
        val baseline = MilestoneEvaluator.strongPostconditionBaseline(milestone, targetPlan, before, "primary.app", bindings, "run-control")
        assertEquals(false, baseline["ready"])
        assertTrue(bindings.commitMutation(prepared, actionKey, before.observationId, baseline))
        assertTrue(bindings.markResultObserved(prepared, actionKey))
        assertTrue(MilestoneEvaluator.evaluate(milestone, targetPlan, after, "primary.app", bindings, runId = "run-control").proven)
    }

    @Test
    fun recreatedWindowAndPackageSwitchCannotProveDisappearance() {
        val milestone = TaskMilestone(
            "dismiss",
            "dismiss target",
            listOf(
                UiPredicate(UiPredicateKind.ELEMENT_DISAPPEARED, predicateId = "dismiss-p1", targetHint = "Dismiss", description = "dismissed"),
                UiPredicate(UiPredicateKind.TEXT_PRESENT, predicateId = "dismiss-p2", literal = "Ready", description = "ready"),
            ),
        )
        val targetPlan = plan.copy(milestones = listOf(milestone))
        val original = UiNodeSnapshot(1, "Dismiss", "", "Button", true, false, "0,0,100,30", viewId = "primary:id/dismiss", treePath = listOf(0, 1), packageName = "primary.app", windowId = 10)
        val before = Observation("primary.app", listOf(original), windowIds = setOf(10))
        val bindings = PredicateBindingStore()
        val ledger = RunLedger(targetPlan)
        val action = AgentAction.ClickNode(1, predicateId = "dismiss-p1")
        val prepared = bindings.prepareActionBinding(milestone, action, before, "run-1")
        val actionKey = ledger.actionKey(action, before)
        val baseline = MilestoneEvaluator.strongPostconditionBaseline(milestone, targetPlan, before, "primary.app", bindings, "run-1")
        assertTrue(bindings.commitMutation(prepared, actionKey, before.observationId, baseline))
        val recreated = Observation(
            "primary.app",
            listOf(
                original.copy(id = 2, windowId = 11),
                UiNodeSnapshot(3, "Ready", "", "TextView", false, false, "0,40,100,70", packageName = "primary.app", windowId = 11),
            ),
            windowIds = setOf(11),
        )
        assertTrue(bindings.markResultObserved(prepared, actionKey))
        assertEquals(IdentityResolution.WindowRecreated, NodeSelector.resolveIdentity(recreated, BoundElementIdentity.from(original)))
        assertFalse(MilestoneEvaluator.evaluate(milestone, targetPlan, recreated, "primary.app", bindings, runId = "run-1").proven)
        val switched = Observation("secondary.app", emptyList())
        assertEquals(IdentityResolution.PackageChanged, NodeSelector.resolveIdentity(switched, BoundElementIdentity.from(original)))
        assertFalse(MilestoneEvaluator.evaluate(milestone, targetPlan, switched, "primary.app", bindings, runId = "run-1").proven)
    }

    @Test
    fun boundPredicateAllowsSubsequentActionWithSameIdAndRejectsConflict() {
        val milestone = TaskMilestone(
            "m1",
            "dismiss",
            listOf(UiPredicate(UiPredicateKind.ELEMENT_DISAPPEARED, predicateId = "m1-p1", targetHint = "Dismiss", description = "gone")),
        )
        val screen = Observation("primary.app", listOf(UiNodeSnapshot(1, "Dismiss", "", "Button", true, false, "0,0,100,30", packageName = "primary.app")))
        val bindings = PredicateBindingStore()
        val bind = bindings.prepareActionBinding(milestone, AgentAction.BindPredicate("m1-p1", nodeId = 1), screen, "run-1")
        assertTrue(bindings.commitAll(bind.provisional))
        assertTrue(bindings.prepareActionBinding(milestone, AgentAction.ClickNode(1, predicateId = "m1-p1"), screen, "run-1").prepared)
        val conflictScreen = screen.copy(nodes = listOf(screen.nodes.single().copy(id = 2, bounds = "0,40,100,70")))
        assertFalse(bindings.prepareActionBinding(milestone, AgentAction.ClickNode(2, predicateId = "m1-p1"), conflictScreen, "run-1").prepared)
    }

    @Test
    fun singleExistingBindingCanBeInferredWithoutPredicateId() {
        val milestone = TaskMilestone(
            "m1",
            "dismiss",
            listOf(UiPredicate(UiPredicateKind.ELEMENT_DISAPPEARED, predicateId = "m1-p1", targetHint = "Dismiss", description = "gone")),
        )
        val screen = Observation("primary.app", listOf(UiNodeSnapshot(1, "Dismiss", "", "Button", true, false, "0,0,100,30", packageName = "primary.app")))
        val bindings = PredicateBindingStore()
        val initial = bindings.prepareActionBinding(milestone, AgentAction.ClickNode(1, predicateId = "m1-p1"), screen, "run-1")
        assertTrue(bindings.commitAll(initial.provisional))
        val inferred = bindings.prepareActionBinding(milestone, AgentAction.ClickNode(1), screen, "run-1")
        assertTrue(inferred.prepared)
        assertEquals("m1-p1", inferred.inferredExistingBinding?.predicateId)
    }

    @Test
    fun singleExistingBindingRejectsDifferentTargetWithoutPredicateId() {
        val milestone = TaskMilestone(
            "m1",
            "dismiss",
            listOf(UiPredicate(UiPredicateKind.ELEMENT_DISAPPEARED, predicateId = "m1-p1", targetHint = "Dismiss", description = "gone")),
        )
        val screen = Observation("primary.app", listOf(
            UiNodeSnapshot(1, "Dismiss", "", "Button", true, false, "0,0,100,30", packageName = "primary.app"),
            UiNodeSnapshot(2, "Other", "", "Button", true, false, "0,40,100,70", packageName = "primary.app"),
        ))
        val bindings = PredicateBindingStore()
        val initial = bindings.prepareActionBinding(milestone, AgentAction.ClickNode(1, predicateId = "m1-p1"), screen, "run-1")
        assertTrue(bindings.commitAll(initial.provisional))
        assertFalse(bindings.prepareActionBinding(milestone, AgentAction.ClickNode(2), screen, "run-1").prepared)
    }

    @Test
    fun windowRecreatedStatePredicateCanRebindButDisappearedCannot() {
        val state = TaskMilestone(
            "m1",
            "toggle",
            listOf(UiPredicate(UiPredicateKind.TOGGLE_STATE, predicateId = "m1-p1", expectedChecked = true, targetHint = "Target", description = "on")),
        )
        val oldNode = UiNodeSnapshot(1, "Target", "", "Switch", true, false, "0,0,100,30", checked = false, viewId = "primary:id/target", treePath = listOf(0, 1), packageName = "primary.app", windowId = 1)
        val old = Observation("primary.app", listOf(oldNode), windowIds = setOf(1))
        val bindings = PredicateBindingStore()
        val first = bindings.prepareActionBinding(state, AgentAction.EnsureToggle(1, true, predicateId = "m1-p1"), old, "run-1")
        assertTrue(bindings.commitAll(first.provisional))
        val storedBeforeRebind = bindings.get("m1", 0)
        val newNode = oldNode.copy(id = 9, windowId = 2, checked = false)
        val recreated = Observation("primary.app", listOf(newNode), windowIds = setOf(2))
        val rebind = bindings.prepareActionBinding(state, AgentAction.EnsureToggle(9, true), recreated, "run-1")
        assertTrue(rebind.prepared)
        assertEquals("rebind_required", rebind.reason)
        assertEquals(storedBeforeRebind, bindings.get("m1", 0))
        assertTrue(bindings.commitObservation(rebind, BindingOrigin.OBSERVATION_ONLY))
        assertEquals(2, bindings.get("m1", 0)?.identity?.windowId)

        val disappeared = TaskMilestone("gone", "gone", listOf(UiPredicate(UiPredicateKind.ELEMENT_DISAPPEARED, predicateId = "gone-p1", targetHint = "Target", description = "gone")))
        val goneBindings = PredicateBindingStore()
        val goneFirst = goneBindings.prepareActionBinding(disappeared, AgentAction.ClickNode(1, predicateId = "gone-p1"), old, "run-2")
        assertTrue(goneBindings.commitAll(goneFirst.provisional))
        assertFalse(goneBindings.prepareActionBinding(disappeared, AgentAction.ClickNode(9), recreated, "run-2").prepared)
    }

    @Test
    fun rebindRespectsExplicitSelectorAndFailedExecutionLeavesOldBinding() {
        val predicate = UiPredicate(
            UiPredicateKind.TOGGLE_STATE,
            predicateId = "toggle-p1",
            expectedChecked = true,
            target = ElementSelector(
                packageName = "primary.app",
                viewIdResourceName = "primary:id/toggle",
                className = "Switch",
            ),
            description = "toggle on",
        )
        val milestone = TaskMilestone("toggle", "toggle", listOf(predicate))
        val oldNode = UiNodeSnapshot(
            1,
            "Target",
            "",
            "Switch",
            true,
            false,
            "0,0,100,30",
            viewId = "primary:id/toggle",
            treePath = listOf(0, 1),
            checked = false,
            packageName = "primary.app",
            windowId = 10,
        )
        val old = Observation("primary.app", listOf(oldNode), windowIds = setOf(10))
        val bindings = PredicateBindingStore()
        val first = bindings.prepareActionBinding(milestone, AgentAction.EnsureToggle(1, true, predicateId = "toggle-p1"), old, "run-1")
        assertTrue(bindings.commitObservation(first, BindingOrigin.OBSERVATION_ONLY))
        val stored = bindings.get("toggle", 0)

        val recreatedNode = oldNode.copy(id = 2, windowId = 11)
        val recreated = Observation("primary.app", listOf(recreatedNode), windowIds = setOf(11))
        val preparedRebind = bindings.prepareActionBinding(
            milestone,
            AgentAction.EnsureToggle(2, true, predicateId = "toggle-p1"),
            recreated,
            "run-1",
        )
        assertTrue(preparedRebind.prepared)
        assertEquals(stored, bindings.get("toggle", 0))
        // Simulated executeDetailed=false: no transaction commit is allowed.
        assertEquals(stored, bindings.get("toggle", 0))

        val changedViewNode = recreatedNode.copy(id = 3, viewId = "primary:id/other", windowId = 12)
        val changedView = Observation("primary.app", listOf(changedViewNode), windowIds = setOf(12))
        val rejected = bindings.prepareActionBinding(
            milestone,
            AgentAction.EnsureToggle(3, true, predicateId = "toggle-p1"),
            changedView,
            "run-1",
        )
        assertFalse(rejected.prepared)
        assertEquals(stored, bindings.get("toggle", 0))
    }

    @Test
    fun productionKeysDetectWindowRecreationAndStaleExecutionObservation() {
        val path = listOf(0, 2)
        val original = UiNodeSnapshot(
            1,
            "Target",
            "",
            "Switch",
            true,
            false,
            "0,0,100,30",
            withinWindowStableKey = AgentAccessibilityService.stableNodeKey(
                "primary.app", 10, "primary:id/target", "Switch", path,
            ),
            crossWindowStructureKey = AgentAccessibilityService.crossWindowStructureKey(
                "primary.app", "primary:id/target", "Switch", path,
            ),
            viewId = "primary:id/target",
            treePath = path,
            packageName = "primary.app",
            windowId = 10,
        )
        val replacement = original.copy(
            id = 2,
            windowId = 11,
            withinWindowStableKey = AgentAccessibilityService.stableNodeKey(
                "primary.app", 11, "primary:id/target", "Switch", path,
            ),
        )
        val identity = BoundElementIdentity.from(original)
        val before = Observation("primary.app", listOf(original), windowIds = setOf(10), windowPackages = mapOf(10 to "primary.app"))
        val after = Observation("primary.app", listOf(replacement), windowIds = setOf(11), windowPackages = mapOf(11 to "primary.app"))
        assertFalse(original.withinWindowStableKey == replacement.withinWindowStableKey)
        assertEquals(original.crossWindowStructureKey, replacement.crossWindowStructureKey)
        assertEquals(IdentityResolution.WindowRecreated, NodeSelector.resolveIdentity(after, identity))

        val milestone = TaskMilestone(
            "m1",
            "target remains present",
            listOf(UiPredicate(UiPredicateKind.ELEMENT_PRESENT, targetHint = "Target", description = "target present")),
        )
        val runtimePlan = TaskPlan("target", "primary.app", GoalContext("target"), listOf(milestone))
        val preflight = RuntimeStepExecutor.preflight(
            guard = ToolGuard(runtimePlan, "primary.app"),
            ledger = RunLedger(runtimePlan),
            proposed = AgentAction.ClickNode(1),
            planningObservation = before,
            executionObservation = after,
            milestone = milestone,
            packagePolicy = PackagePolicy(mutableSetOf("primary.app"), "primary.app"),
            launchablePackages = setOf("primary.app"),
            goal = runtimePlan.goal,
        )
        assertTrue(preflight.stale)
    }

    @Test
    fun observationFingerprintIncludesWindowTopologyAndWithinWindowKey() {
        val path = listOf(0, 1)
        fun node(windowId: Int) = UiNodeSnapshot(
            1,
            "Target",
            "",
            "Button",
            true,
            false,
            "0,0,100,30",
            withinWindowStableKey = AgentAccessibilityService.stableNodeKey(
                "primary.app", windowId, "primary:id/target", "Button", path,
            ),
            crossWindowStructureKey = AgentAccessibilityService.crossWindowStructureKey(
                "primary.app", "primary:id/target", "Button", path,
            ),
            viewId = "primary:id/target",
            treePath = path,
            packageName = "primary.app",
            windowId = windowId,
        )
        val first = Observation("primary.app", listOf(node(10)), windowIds = setOf(10), windowPackages = mapOf(10 to "primary.app"))
        val recreated = Observation("primary.app", listOf(node(11)), windowIds = setOf(11), windowPackages = mapOf(11 to "primary.app"))
        val topologyOnly = Observation("primary.app", emptyList(), windowIds = setOf(10), windowPackages = mapOf(10 to "primary.app"))
        val changedTopologyOnly = Observation("primary.app", emptyList(), windowIds = setOf(11), windowPackages = mapOf(11 to "primary.app"))

        assertFalse(first.observationId == recreated.observationId)
        assertFalse(topologyOnly.observationId == changedTopologyOnly.observationId)
    }

    @Test
    fun multipleCompatiblePredicatesRequireExplicitPredicateId() {
        val milestone = TaskMilestone(
            "state",
            "inspect target",
            listOf(
                UiPredicate(UiPredicateKind.ELEMENT_PRESENT, predicateId = "state-p1", description = "first"),
                UiPredicate(UiPredicateKind.ELEMENT_PRESENT, predicateId = "state-p2", description = "second"),
            ),
        )
        val screen = Observation("primary.app", listOf(
            UiNodeSnapshot(1, "Target", "", "Button", true, false, "0,0,100,30", packageName = "primary.app"),
        ))
        val bindings = PredicateBindingStore()
        assertFalse(bindings.prepareActionBinding(milestone, AgentAction.ClickNode(1), screen).prepared)
        assertTrue(bindings.prepareActionBinding(milestone, AgentAction.ClickNode(1, predicateId = "state-p2"), screen).prepared)
    }

    @Test
    fun alreadySatisfiedToggleIsAComposedShortCircuit() {
        val milestone = TaskMilestone(
            "toggle",
            "toggle target",
            listOf(UiPredicate(UiPredicateKind.TOGGLE_STATE, expectedChecked = true, targetHint = "Target", description = "target on")),
        )
        val toggle = UiNodeSnapshot(1, "Target", "", "Switch", true, false, "0,0,100,30", checked = true, packageName = "primary.app")
        val guard = ToolGuard(plan.copy(milestones = listOf(milestone)), "primary.app")
        val result = guard.normalizeAndValidate(AgentAction.EnsureToggle(1, true), Observation("primary.app", listOf(toggle)), milestone)
        assertEquals("already_satisfied", result.shortCircuit?.status)
        assertEquals(null, result.action)
    }

    @Test
    fun directBindRejectsDifferentNodeAndAllowsIdempotentSameNodeBind() {
        val milestone = TaskMilestone(
            "m1",
            "bind target",
            listOf(UiPredicate(UiPredicateKind.ELEMENT_PRESENT, predicateId = "m1-p1", targetHint = "Target", description = "target present")),
        )
        val screen = Observation("primary.app", listOf(
            UiNodeSnapshot(1, "Target", "", "Button", true, false, "0,0,100,30", packageName = "primary.app"),
            UiNodeSnapshot(2, "Other", "", "Button", true, false, "0,40,100,70", packageName = "primary.app"),
        ))
        val bindings = PredicateBindingStore()
        assertTrue(bindings.bind(milestone, 0, screen.nodes[0], screen, "run-1").bound)
        assertTrue(bindings.bind(milestone, 0, screen.nodes[0], screen, "run-1").bound)
        assertFalse(bindings.bind(milestone, 0, screen.nodes[1], screen, "run-1").bound)
    }

    @Test
    fun verifiedPredicateCannotReceiveAnotherAction() {
        val milestone = TaskMilestone(
            "m1",
            "dismiss target",
            listOf(UiPredicate(UiPredicateKind.ELEMENT_DISAPPEARED, predicateId = "m1-p1", targetHint = "Dismiss", description = "gone")),
        )
        val screen = Observation("primary.app", listOf(UiNodeSnapshot(1, "Dismiss", "", "Button", true, false, "0,0,100,30", packageName = "primary.app")))
        val bindings = PredicateBindingStore()
        val prepared = bindings.prepareActionBinding(milestone, AgentAction.ClickNode(1, predicateId = "m1-p1"), screen, "run-1")
        assertTrue(bindings.commitAll(prepared.provisional))
        bindings.markVerified("m1")
        assertFalse(bindings.prepareActionBinding(milestone, AgentAction.ClickNode(1, predicateId = "m1-p1"), screen, "run-1").prepared)
    }

    @Test
    fun settleTimeoutKeepsCommittedBindingAndBlocksImmediateDuplicate() {
        val milestone = TaskMilestone(
            "m1",
            "dismiss target",
            listOf(UiPredicate(UiPredicateKind.ELEMENT_DISAPPEARED, predicateId = "m1-p1", targetHint = "Dismiss", description = "gone")),
        )
        val plan = TaskPlan("dismiss", "primary.app", GoalContext("dismiss"), listOf(milestone))
        val path = listOf(0, 1)
        val screen = Observation(
            "primary.app",
            listOf(UiNodeSnapshot(
                1,
                "Dismiss",
                "",
                "Button",
                true,
                false,
                "0,0,100,30",
                withinWindowStableKey = AgentAccessibilityService.stableNodeKey("primary.app", 4, "primary:id/dismiss", "Button", path),
                crossWindowStructureKey = AgentAccessibilityService.crossWindowStructureKey("primary.app", "primary:id/dismiss", "Button", path),
                viewId = "primary:id/dismiss",
                treePath = path,
                packageName = "primary.app",
                windowId = 4,
            )),
            windowIds = setOf(4),
        )
        val bindings = PredicateBindingStore()
        val ledger = RunLedger(plan)
        val action = AgentAction.ClickNode(1, predicateId = "m1-p1")
        val prepared = bindings.prepareActionBinding(milestone, action, screen, "run-1")
        val actionKey = ledger.actionKey(action, screen)
        assertTrue(bindings.commitMutation(prepared, actionKey, screen.observationId, emptyMap()))
        assertTrue(bindings.markResultUnknown(prepared, actionKey))
        assertEquals(BindingLifecycle.COMMITTED, bindings.get("m1", 0)?.lifecycle)
        assertEquals(BindingOrigin.MUTATING_ACTION, bindings.get("m1", 0)?.origin)
        assertEquals(BindingResultState.RESULT_UNKNOWN, bindings.get("m1", 0)?.resultState)

        ledger.recordDispatch(action, screen, DispatchResultState.RESULT_UNKNOWN)
        assertTrue(ledger.blockRepeated(action, screen) != null)
        assertEquals(BindingLifecycle.COMMITTED, bindings.get("m1", 0)?.lifecycle)
    }
}
