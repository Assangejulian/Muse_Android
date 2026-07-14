package com.androidagent.app.agent

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
        assertEquals(IdentityResolution.MissingInSameContext, NodeSelector.resolveIdentity(Observation("primary.app", listOf(UiNodeSnapshot(2, "Other", "", "Switch", true, false, "0,40,100,70", viewId = "primary:id/other", packageName = "primary.app", windowId = 3))), identity))
        assertEquals(IdentityResolution.PackageChanged, NodeSelector.resolveIdentity(Observation("secondary.app", emptyList()), identity))
        assertEquals(IdentityResolution.WindowChanged, NodeSelector.resolveIdentity(Observation("primary.app", listOf(original.copy(windowId = 4))), identity))
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
}
