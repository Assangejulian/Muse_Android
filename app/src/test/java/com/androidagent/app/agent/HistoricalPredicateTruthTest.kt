package com.androidagent.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoricalPredicateTruthTest {
    @Test
    fun newlyBoundControlWasRefutedInCompletePreDispatchSnapshot() {
        val predicate = presentPredicate(targetHint = "Success")
        val before = Observation("primary.app", emptyList())
        val afterNode = node(1, "Success", "primary:id/success", "success-key", listOf(0, 1))
        val binding = bind(predicate, afterNode)

        assertEquals(
            PredicateTruth.REFUTED,
            historical(predicate, snapshot(before), binding),
        )
    }

    @Test
    fun missingViewIdAndMissingStructureKeyAreRefuted() {
        val predicate = presentPredicate(targetHint = "Success")
        val before = Observation("primary.app", listOf(node(9, "Other", "primary:id/other", "other-key", listOf(0, 0))))

        val byViewId = bind(
            predicate,
            node(1, "Success", "primary:id/success", "success-key", listOf(0, 1)),
        )
        assertEquals(PredicateTruth.REFUTED, historical(predicate, snapshot(before), byViewId))

        val byStructure = bind(
            predicate,
            node(2, "Success", "", "success-structure", listOf(0, 2)),
        )
        assertEquals(PredicateTruth.REFUTED, historical(predicate, snapshot(before), byStructure))
    }

    @Test
    fun ambiguousHistoricalIdentityIsUnknown() {
        val predicate = presentPredicate(targetHint = "Success")
        val afterNode = node(1, "Success", "primary:id/success", "success-key", null)
        val binding = bind(predicate, afterNode)
        val before = Observation(
            "primary.app",
            listOf(
                node(4, "Old", "primary:id/success", "first", null),
                node(5, "Old", "primary:id/success", "second", null).copy(bounds = "0,50,100,90"),
            ),
        )
        assertEquals(PredicateTruth.UNKNOWN, historical(predicate, snapshot(before), binding))
    }

    @Test
    fun targetHintOnlyPrivacyFilteredAndIncompleteSnapshotsRemainUnknown() {
        val hintOnly = presentPredicate(targetHint = "Success")
        assertEquals(PredicateTruth.UNKNOWN, historical(hintOnly, snapshot(Observation("primary.app", emptyList())), null))

        val explicit = presentPredicate(
            selector = ElementSelector(packageName = "primary.app", viewIdResourceName = "primary:id/success"),
        )
        val privacyFiltered = snapshot(Observation("primary.app", emptyList(), privacyFiltered = true))
        val incomplete = snapshot(Observation("primary.app", emptyList(), isComplete = false))
        assertEquals(PredicateTruth.UNKNOWN, historical(explicit, privacyFiltered, null))
        assertEquals(PredicateTruth.UNKNOWN, historical(explicit, incomplete, null))
        assertTrue(privacyFiltered.privacyFiltered)
        assertFalse(incomplete.isComplete)
    }

    @Test
    fun completeRefutedToProvenUnlocksBoundWindowGone() {
        val fixture = boundWindowFixture(beforeComplete = true)
        val evidence = MilestoneEvaluator.evaluate(
            fixture.milestone,
            fixture.plan,
            fixture.after,
            "primary.app",
            fixture.bindings,
            runId = "run-window",
            preDispatchSnapshots = fixture.snapshots,
        )
        assertTrue(evidence.proven)
        assertEquals(PredicateTruth.PROVEN, evidence.truthFor("gone"))
    }

    @Test
    fun incompleteUnknownToProvenCannotUnlockBoundWindowGone() {
        val fixture = boundWindowFixture(beforeComplete = false)
        val evidence = MilestoneEvaluator.evaluate(
            fixture.milestone,
            fixture.plan,
            fixture.after,
            "primary.app",
            fixture.bindings,
            runId = "run-window",
            preDispatchSnapshots = fixture.snapshots,
        )
        assertFalse(evidence.proven)
        assertEquals(PredicateTruth.UNKNOWN, evidence.truthFor("gone"))
        assertTrue(evidence.replanRequired)
    }

    @Test
    fun provenToProvenDoesNotUnlockBoundWindowGone() {
        val fixture = boundWindowFixture(beforeComplete = true, readyAlreadyPresent = true)
        val evidence = MilestoneEvaluator.evaluate(
            fixture.milestone,
            fixture.plan,
            fixture.after,
            "primary.app",
            fixture.bindings,
            runId = "run-window",
            preDispatchSnapshots = fixture.snapshots,
        )
        assertFalse(evidence.proven)
        assertEquals(PredicateTruth.UNKNOWN, evidence.truthFor("gone"))
    }

    private fun boundWindowFixture(
        beforeComplete: Boolean,
        readyAlreadyPresent: Boolean = false,
    ): BoundWindowFixture {
        val readySelector = ElementSelector(
            packageName = "primary.app",
            viewIdResourceName = "primary:id/ready",
            className = "android.widget.ImageView",
        )
        val milestone = TaskMilestone(
            "dismiss",
            "dismiss dialog",
            listOf(
                UiPredicate(
                    UiPredicateKind.ELEMENT_DISAPPEARED,
                    predicateId = "gone",
                    target = ElementSelector(packageName = "primary.app", viewIdResourceName = "primary:id/dialog"),
                    description = "dialog disappears",
                ),
                UiPredicate(
                    UiPredicateKind.ELEMENT_PRESENT,
                    predicateId = "ready",
                    target = readySelector,
                    description = "ready icon appears",
                ),
            ),
        )
        val plan = TaskPlan("dismiss", "primary.app", GoalContext("dismiss"), listOf(milestone))
        val dialog = UiNodeSnapshot(
            1,
            "Dialog",
            "",
            "android.widget.Button",
            true,
            false,
            "0,0,100,40",
            viewId = "primary:id/dialog",
            treePath = listOf(0, 1),
            packageName = "primary.app",
            windowId = 7,
        )
        val readyBefore = UiNodeSnapshot(
            3,
            "",
            "Ready",
            "android.widget.ImageView",
            false,
            false,
            "0,50,40,90",
            viewId = "primary:id/ready",
            treePath = listOf(1, 0),
            packageName = "primary.app",
            windowId = 6,
        )
        val before = Observation(
            "primary.app",
            if (readyAlreadyPresent) listOf(dialog, readyBefore) else listOf(dialog),
            windowIds = if (readyAlreadyPresent) setOf(6, 7) else setOf(7),
            windowPackages = if (readyAlreadyPresent) {
                mapOf(6 to "primary.app", 7 to "primary.app")
            } else mapOf(7 to "primary.app"),
            isComplete = beforeComplete,
        )
        val ready = UiNodeSnapshot(
            2,
            "",
            "Ready",
            "android.widget.ImageView",
            false,
            false,
            "0,0,40,40",
            viewId = "primary:id/ready",
            treePath = listOf(0, 0),
            packageName = "primary.app",
            windowId = 8,
        )
        val after = Observation(
            "primary.app",
            listOf(ready),
            windowIds = setOf(8),
            windowPackages = mapOf(8 to "primary.app"),
        )
        val action = AgentAction.ClickNode(1, predicateId = "gone")
        val resolved = TargetResolver.resolveActionTarget(action, before).target!!
        val identity = SideEffectIdentityFactory.create(action, before, resolvedTarget = resolved)!!
        val snapshots = PreDispatchEvidenceStore()
        val captured = snapshots.capture(before, identity, 1)
        val bindings = PredicateBindingStore()
        val preparation = bindings.prepareActionBinding(milestone, action, before, "run-window", resolved)
        assertTrue(
            bindings.commitMutation(
                preparation,
                actionKey = "dismiss-action",
                observationId = before.observationId,
                strongPostconditionsBefore = mapOf("ready" to false),
                preDispatchSnapshotId = captured.snapshotId,
                strongPostconditionTruthBefore = mapOf("ready" to PredicateTruth.UNKNOWN),
            ),
        )
        assertTrue(bindings.markResultObserved(preparation, "dismiss-action"))
        return BoundWindowFixture(milestone, plan, after, bindings, snapshots)
    }

    private fun historical(
        predicate: UiPredicate,
        snapshot: PreDispatchEvidenceSnapshot,
        binding: PredicateBinding?,
    ) = MilestoneEvaluator.evaluateHistoricalPredicateTruth(
        predicate = predicate,
        predicateId = predicate.predicateId!!,
        preDispatchSnapshot = snapshot,
        postActionBinding = binding,
        targetPackage = "primary.app",
    )

    private fun bind(predicate: UiPredicate, target: UiNodeSnapshot): PredicateBinding {
        val milestone = TaskMilestone("state", "state", listOf(predicate))
        val observation = Observation(
            "primary.app",
            listOf(target),
            windowIds = target.windowId?.let(::setOf) ?: emptySet(),
            windowPackages = target.windowId?.let { mapOf(it to "primary.app") } ?: emptyMap(),
        )
        return PredicateBindingStore().bind(milestone, 0, target, observation, "run-history").binding!!
    }

    private fun snapshot(observation: Observation): PreDispatchEvidenceSnapshot = PreDispatchEvidenceStore().capture(
        observation,
        SideEffectIdentity(
            actionType = SideEffectFamily.ACTIVATE_CONTROL.name,
            targetPackage = "primary.app",
            targetCrossWindowStructureKey = "dispatch-target",
            family = SideEffectFamily.ACTIVATE_CONTROL,
        ),
        1,
    )

    private fun presentPredicate(
        targetHint: String? = null,
        selector: ElementSelector? = null,
    ) = UiPredicate(
        UiPredicateKind.ELEMENT_PRESENT,
        predicateId = "success",
        targetHint = targetHint,
        target = selector,
        description = "success control appears",
    )

    private fun node(
        id: Int,
        text: String,
        viewId: String,
        structureKey: String,
        path: List<Int>?,
    ) = UiNodeSnapshot(
        id = id,
        text = text,
        description = "",
        className = "android.widget.ImageView",
        clickable = false,
        editable = false,
        bounds = "0,0,100,40",
        crossWindowStructureKey = structureKey,
        viewId = viewId,
        treePath = path,
        packageName = "primary.app",
        windowId = 9,
    )

    private data class BoundWindowFixture(
        val milestone: TaskMilestone,
        val plan: TaskPlan,
        val after: Observation,
        val bindings: PredicateBindingStore,
        val snapshots: PreDispatchEvidenceStore,
    )
}
