package com.androidagent.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimePlanContractTest {
    @Test
    fun operationalAbortIsNotMisreportedAsInternalCrash() {
        assertEquals(RuntimeOutcome.PERMANENT_PLAN_ERROR, classifyOperationalFailure("recovery budget exhausted"))
        assertEquals(RuntimeOutcome.ACCESSIBILITY_DISCONNECTED, classifyOperationalFailure("accessibility service disconnected"))
        assertEquals(RuntimeOutcome.TRANSIENT_NETWORK_ERROR, classifyOperationalFailure("network backoff failed"))
    }

    @Test
    fun launchMilestoneWithTargetHintIsCanonicalizedToPackageEvidence() {
        val managerPlan = TaskPlan(
            summary = "launch target",
            targetAppHint = "Target",
            goal = GoalContext("launch target"),
            milestones = listOf(
                TaskMilestone(
                    id = "m1",
                    objective = "Open the target app",
                    successPredicates = listOf(
                        UiPredicate(
                            kind = UiPredicateKind.ELEMENT_PRESENT,
                            targetHint = "the app home screen",
                            description = "the home screen is visible",
                            predicateId = "m1-p1",
                        ),
                    ),
                    kind = TaskMilestoneKind.LAUNCH_APP,
                ),
            ),
        )

        val normalized = normalizePrimaryLaunchContract(managerPlan, "target.app")
        val milestone = normalized.milestones.single()
        val predicate = milestone.successPredicates.single()

        assertEquals(TaskMilestoneKind.LAUNCH_APP, milestone.kind)
        assertEquals(UiPredicateKind.PACKAGE_FOREGROUND, predicate.kind)
        assertEquals("target.app", predicate.targetPackage)
        assertEquals("m1-p1", predicate.predicateId)
        assertTrue(
            MilestoneEvaluator.evaluate(
                milestone,
                normalized,
                Observation("target.app", emptyList()),
                "target.app",
            ).proven,
        )
    }

    @Test
    fun laterLaunchMilestoneDoesNotSuppressPrimaryLaunchContract() {
        val managerPlan = TaskPlan(
            summary = "cross app task",
            targetAppHint = "Primary",
            goal = GoalContext("cross app task"),
            milestones = listOf(
                TaskMilestone(
                    id = "m1",
                    objective = "Inspect primary",
                    successPredicates = listOf(
                        UiPredicate(
                            kind = UiPredicateKind.TEXT_PRESENT,
                            literal = "Ready",
                            description = "ready text is visible",
                            predicateId = "m1-p1",
                        ),
                    ),
                ),
                TaskMilestone(
                    id = "m2",
                    objective = "Open secondary",
                    successPredicates = listOf(
                        UiPredicate(
                            kind = UiPredicateKind.PACKAGE_FOREGROUND,
                            targetPackage = "secondary.app",
                            description = "secondary is foreground",
                            predicateId = "m2-p1",
                        ),
                    ),
                    kind = TaskMilestoneKind.LAUNCH_APP,
                ),
            ),
        )

        val normalized = normalizePrimaryLaunchContract(managerPlan, "primary.app")

        assertEquals(3, normalized.milestones.size)
        assertEquals(TaskMilestoneKind.LAUNCH_APP, normalized.milestones.first().kind)
        assertEquals("primary.app", normalized.milestones.first().successPredicates.single().targetPackage)
        assertEquals("secondary.app", normalized.milestones.last().successPredicates.single().targetPackage)
    }

    @Test
    fun primaryLaunchNormalizationIsIdempotentAcrossReplans() {
        val plan = TaskPlan(
            summary = "launch",
            targetAppHint = "Target",
            goal = GoalContext("launch"),
            milestones = listOf(
                TaskMilestone(
                    id = "m1",
                    objective = "Launch",
                    successPredicates = listOf(
                        UiPredicate(
                            kind = UiPredicateKind.PACKAGE_FOREGROUND,
                            targetPackage = "target.app",
                            description = "target is foreground",
                            predicateId = "m1-p1",
                        ),
                    ),
                    kind = TaskMilestoneKind.LAUNCH_APP,
                ),
            ),
        )

        val once = normalizePrimaryLaunchContract(plan, "target.app")
        val twice = normalizePrimaryLaunchContract(once, "target.app")

        assertEquals(once, twice)
    }
}
