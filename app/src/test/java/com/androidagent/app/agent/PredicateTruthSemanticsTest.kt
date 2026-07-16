package com.androidagent.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PredicateTruthSemanticsTest {
    @Test
    fun targetHintOnlyAmbiguityIsUnknownInsteadOfRefuted() {
        val milestone = TaskMilestone(
            "m1",
            "find target",
            listOf(UiPredicate(UiPredicateKind.ELEMENT_PRESENT, predicateId = "present-p1", targetHint = "Target", description = "target is present")),
        )
        val plan = plan(milestone)
        val observation = Observation(
            "primary.app",
            listOf(node(1, "Target", "Button"), node(2, "Target", "Button")),
        )
        val evidence = MilestoneEvaluator.evaluate(milestone, plan, observation, "primary.app")
        assertEquals(PredicateTruth.UNKNOWN, evidence.truthFor("present-p1"))
        assertFalse(evidence.proven)
    }

    @Test
    fun privacyFilteredTextCannotBeTreatedAsAbsent() {
        val milestone = TaskMilestone(
            "m1",
            "find text",
            listOf(UiPredicate(UiPredicateKind.TEXT_PRESENT, predicateId = "text-p1", literal = "secret", description = "secret is visible")),
        )
        val observation = Observation(
            "primary.app",
            listOf(node(1, "[redacted-number]", "TextView")),
            privacyFiltered = true,
        )
        val evidence = MilestoneEvaluator.evaluate(milestone, plan(milestone), observation, "primary.app")
        assertEquals(PredicateTruth.UNKNOWN, evidence.truthFor("text-p1"))
    }

    @Test
    fun visiblePositiveEvidenceRemainsProvenInPartialObservation() {
        val packageMilestone = TaskMilestone(
            "launch",
            "launch target",
            listOf(
                UiPredicate(
                    UiPredicateKind.PACKAGE_FOREGROUND,
                    predicateId = "launch-p1",
                    targetPackage = "primary.app",
                    description = "primary app is foreground",
                ),
            ),
        )
        val textMilestone = TaskMilestone(
            "ready",
            "find ready",
            listOf(UiPredicate(UiPredicateKind.TEXT_PRESENT, predicateId = "ready-p1", literal = "Ready", description = "ready is visible")),
        )
        val partial = Observation("primary.app", listOf(node(1, "Ready", "TextView")), isComplete = false)

        assertEquals(
            PredicateTruth.PROVEN,
            MilestoneEvaluator.evaluatePredicateTruth(packageMilestone, plan(packageMilestone), partial, "primary.app", 0),
        )
        assertEquals(
            PredicateTruth.PROVEN,
            MilestoneEvaluator.evaluatePredicateTruth(textMilestone, plan(textMilestone), partial, "primary.app", 0),
        )
    }

    @Test
    fun missingEvidenceRemainsUnknownInPartialObservation() {
        val milestone = TaskMilestone(
            "m1",
            "find text",
            listOf(UiPredicate(UiPredicateKind.TEXT_PRESENT, predicateId = "ready-p1", literal = "Ready", description = "ready is visible")),
        )
        val partial = Observation("primary.app", emptyList(), isComplete = false)

        assertEquals(
            PredicateTruth.UNKNOWN,
            MilestoneEvaluator.evaluatePredicateTruth(milestone, plan(milestone), partial, "primary.app", 0),
        )
    }

    @Test
    fun actionBeforeAbsentAndAfterUniqueTextAppearsIsProven() {
        val milestone = TaskMilestone(
            "m1",
            "wait for ready",
            listOf(UiPredicate(UiPredicateKind.TEXT_PRESENT, predicateId = "ready-p1", literal = "Ready", description = "ready appears")),
        )
        val before = Observation("primary.app", emptyList())
        val after = Observation("primary.app", listOf(node(1, "Ready", "TextView")))
        assertEquals(PredicateTruth.REFUTED, MilestoneEvaluator.evaluatePredicateTruth(milestone, plan(milestone), before, "primary.app", 0))
        assertEquals(PredicateTruth.PROVEN, MilestoneEvaluator.evaluatePredicateTruth(milestone, plan(milestone), after, "primary.app", 0))
    }

    @Test
    fun bindingCanMoveToRepairMilestoneByGlobalPredicateId() {
        val predicate = UiPredicate(
            UiPredicateKind.ELEMENT_PRESENT,
            predicateId = "gone-p1",
            target = ElementSelector(text = "Target", className = "Button"),
            description = "target remains bound",
        )
        val previous = plan(TaskMilestone("m1", "original", listOf(predicate)))
        val revised = plan(TaskMilestone("repair-m2", "repair", listOf(predicate)))
        val screen = Observation("primary.app", listOf(node(1, "Target", "Button")))
        val bindings = PredicateBindingStore()
        val preparation = bindings.prepareActionBinding(previous.milestones.single(), AgentAction.ClickNode(1), screen, "run-1")
        assertTrue(preparation.prepared)
        assertTrue(
            bindings.commitMutation(
                preparation = preparation,
                actionKey = "click-target",
                observationId = "obs-after",
                strongPostconditionsBefore = mapOf("gone-p1" to false),
                preDispatchSnapshotId = "snapshot-1",
                strongPostconditionTruthBefore = mapOf("gone-p1" to PredicateTruth.REFUTED),
            ),
        )
        val oldBinding = bindings.get("m1", 0)!!
        bindings.retainCompleted(previous, revised, emptySet())
        assertEquals(null, bindings.get("m1", 0))
        val moved = bindings.get("repair-m2", 0)
        assertNotNull(moved)
        assertEquals("repair-m2", moved!!.milestoneId)
        assertEquals(0, moved.predicateIndex)
        assertEquals("gone-p1", moved.predicateId)
        assertEquals(oldBinding.identity, moved.identity)
        assertEquals(oldBinding.origin, moved.origin)
        assertEquals(oldBinding.resultState, moved.resultState)
        assertEquals("snapshot-1", moved.preDispatchSnapshotId)
        assertEquals("click-target", moved.dispatchedActionKey)
        assertEquals("obs-after", moved.dispatchedObservationId)
    }

    @Test
    fun changedPredicateWithSameIdIsRejected() {
        val oldPredicate = UiPredicate(
            UiPredicateKind.TOGGLE_STATE,
            predicateId = "toggle-p1",
            target = ElementSelector(text = "Target", className = "Switch"),
            expectedChecked = true,
            description = "target on",
        )
        val revisedPredicate = oldPredicate.copy(expectedChecked = false)
        val previous = plan(TaskMilestone("m1", "original", listOf(oldPredicate)))
        val revised = plan(TaskMilestone("repair-m2", "repair", listOf(revisedPredicate)))
        assertFalse(runCatching { TaskPlanValidator.requireCompatiblePredicateIds(previous, revised) }.isSuccess)
    }

    @Test
    fun duplicatePredicateIdsInRevisedPlanAreRejectedBeforeBindingRemap() {
        val first = UiPredicate(UiPredicateKind.TEXT_PRESENT, predicateId = "duplicate", literal = "one", description = "one")
        val second = UiPredicate(UiPredicateKind.TEXT_PRESENT, predicateId = "duplicate", literal = "two", description = "two")
        val previous = plan(TaskMilestone("m1", "original", listOf(first)))
        val revised = plan(TaskMilestone("repair-m2", "repair", listOf(first, second)))
        assertFalse(runCatching { TaskPlanValidator.requireCompatiblePredicateIds(previous, revised) }.isSuccess)
    }

    @Test
    fun deletedPredicateBindingIsDroppedDuringReplan() {
        val predicate = UiPredicate(
            UiPredicateKind.ELEMENT_PRESENT,
            predicateId = "gone-p1",
            targetHint = "Target",
            description = "target exists",
        )
        val previous = plan(TaskMilestone("m1", "original", listOf(predicate)))
        val revised = plan(TaskMilestone("repair-m2", "repair", listOf(
            UiPredicate(UiPredicateKind.TEXT_PRESENT, predicateId = "new-p1", literal = "Ready", description = "new state"),
        )))
        val screen = Observation("primary.app", listOf(node(1, "Target", "Button")))
        val bindings = PredicateBindingStore()
        assertTrue(bindings.bind(previous.milestones.single(), 0, screen.nodes.single(), screen, "run-1").bound)
        bindings.retainCompleted(previous, revised, emptySet())
        assertTrue(bindings.all().isEmpty())
    }

    private fun plan(milestone: TaskMilestone) = TaskPlan(
        summary = "predicate truth",
        targetAppHint = "primary.app",
        goal = GoalContext("predicate truth"),
        milestones = listOf(milestone),
    )

    private fun node(id: Int, text: String, className: String) = UiNodeSnapshot(
        id = id,
        text = text,
        description = "",
        className = className,
        clickable = className == "Button",
        editable = false,
        bounds = "0,0,100,40",
        viewId = "primary:id/node_$id",
        packageName = "primary.app",
        windowId = 1,
    )
}
