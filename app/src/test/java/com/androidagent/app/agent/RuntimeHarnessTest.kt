package com.androidagent.app.agent

import kotlinx.coroutines.runBlocking
import java.util.ArrayDeque
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeHarnessTest {
    @Test
    fun fakeRuntimeExercisesGuardExecuteCommitWaitEvaluateAndStopGate() = runBlocking {
        val initial = Observation(
            "example.app",
            listOf(UiNodeSnapshot(1, "Dismiss", "", "Button", true, false, "0,0,100,40", packageName = "example.app")),
        )
        val service = FakeAccessibilityService(initial)
        val planner = FakePlanner { AgentAction.ClickNode(1) }
        val clock = FakeClock()
        val plan = TaskPlan(
            summary = "dismiss",
            targetAppHint = "example.app",
            goal = GoalContext("dismiss the dialog"),
            milestones = listOf(
                TaskMilestone(
                    "m1",
                    "dismiss target",
                    listOf(UiPredicate(UiPredicateKind.ELEMENT_DISAPPEARED, targetHint = "Dismiss", description = "dialog is gone")),
                ),
            ),
        )

        val result = RuntimeContractHarness(
            service = service,
            planner = planner,
            clock = clock,
            launchablePackages = setOf("example.app"),
            packagePolicy = PackagePolicy(allowedPackages = mutableSetOf("example.app"), primaryPackage = "example.app"),
        ).run(plan)

        assertTrue(result.completed)
        val phases = result.events.map { it.phase }
        assertTrue(phases.indexOf("fresh_observation") < phases.indexOf("tool_guard"))
        assertTrue(phases.indexOf("tool_guard") < phases.indexOf("safety_guard"))
        assertTrue(phases.indexOf("safety_guard") < phases.indexOf("prepare_binding"))
        assertTrue(phases.indexOf("prepare_binding") < phases.indexOf("execute"))
        assertTrue(phases.indexOf("execute") < phases.indexOf("commit"))
        assertTrue(phases.indexOf("commit") < phases.indexOf("wait"))
        assertTrue(phases.indexOf("wait") < phases.indexOf("evaluate"))
        assertTrue(service.recoveryActions.isEmpty())
        assertTrue(clock.calls > 0)
    }

    @Test
    fun staleObservationIsRecoveredBeforeBindingOrExecution() = runBlocking {
        val initial = Observation("example.app", listOf(node(1, "Dismiss")))
        val changed = Observation("example.app", emptyList())
        val service = FakeAccessibilityService(initial, observationQueue = listOf(initial, changed))
        val result = harness(service, FakePlanner { AgentAction.ClickNode(1) }, FakeClock()).run(disappearedPlan())

        assertEquals(0, service.executeCount)
        assertTrue(result.events.any { it.phase == "stale_observation" })
        assertTrue(result.events.none { it.phase == "prepare_binding" })
    }

    @Test
    fun safetyRejectionNeverCreatesBindingOrExecutesAction() = runBlocking {
        val service = FakeAccessibilityService(
            Observation("other.app", listOf(node(1, "Dismiss", packageName = "other.app"))),
        )
        val policy = PackagePolicy(allowedPackages = mutableSetOf("example.app"), primaryPackage = "example.app")
        val result = RuntimeContractHarness(
            service = service,
            planner = FakePlanner { AgentAction.ClickNode(1) },
            clock = FakeClock(),
            launchablePackages = setOf("example.app", "other.app"),
            packagePolicy = policy,
        ).run(disappearedPlan())

        assertEquals(0, service.executeCount)
        assertTrue(result.events.any { it.phase == "safety_guard" && it.detail == "rejected" })
        assertTrue(result.events.none { it.phase == "prepare_binding" })
    }

    @Test
    fun executionFailureRollsBackBeforeRecovery() = runBlocking {
        val service = FakeAccessibilityService(Observation("example.app", listOf(node(1, "Dismiss"))), executeSucceeds = false)
        var first = true
        val result = harness(service, FakePlanner {
            if (first) {
                first = false
                AgentAction.ClickNode(1)
            } else {
                AgentAction.Finish("stop after injected failure")
            }
        }, FakeClock()).run(disappearedPlan())

        assertEquals(1, service.executeCount)
        assertTrue(service.recoveryActions.isNotEmpty())
        assertTrue(result.events.any { it.phase == "recover" })
        assertTrue(result.events.none { it.phase == "commit" })
    }

    @Test
    fun bindPredicateCanCompleteObservationOnlyMilestone() = runBlocking {
        val service = FakeAccessibilityService(Observation("example.app", listOf(node(1, "Target"))))
        val milestone = TaskMilestone(
            "m1",
            "inspect target",
            listOf(UiPredicate(UiPredicateKind.ELEMENT_PRESENT, predicateId = "m1-p1", targetHint = "Target", description = "target is present")),
        )
        val plan = TaskPlan("inspect", "example.app", GoalContext("inspect"), listOf(milestone))
        val result = harness(service, FakePlanner { AgentAction.BindPredicate("m1-p1", nodeId = 1) }, FakeClock()).run(plan)

        assertTrue(result.completed)
        assertEquals(0, service.executeCount)
        assertTrue(result.events.any { it.phase == "commit" })
    }

    @Test
    fun productionStyleNonEmptyWindowDismissalCompletes() = runBlocking {
        val target = UiNodeSnapshot(
            1, "Dismiss", "", "Button", true, false, "0,0,100,40",
            viewId = "example:id/dismiss", packageName = "example.app", windowId = 7,
        )
        val initial = Observation("example.app", listOf(target), windowIds = setOf(7))
        val service = WindowDismissService(initial)
        val result = RuntimeContractHarness(
            service = service,
            planner = FakePlanner { AgentAction.ClickNode(1) },
            clock = FakeClock(),
            launchablePackages = setOf("example.app"),
            packagePolicy = PackagePolicy(allowedPackages = mutableSetOf("example.app"), primaryPackage = "example.app"),
        ).run(disappearedPlan())

        assertTrue(result.completed)
        val phases = result.events.map { it.phase }
        assertTrue(phases.indexOf("planner") < phases.indexOf("fresh_execution_observation"))
        assertTrue(phases.indexOf("fresh_execution_observation") < phases.indexOf("tool_guard"))
        assertTrue(phases.indexOf("tool_guard") < phases.indexOf("safety_guard"))
        assertTrue(phases.indexOf("safety_guard") < phases.indexOf("prepare_binding"))
        assertTrue(phases.indexOf("mark_dispatched") < phases.indexOf("commit"))
    }

    private fun harness(
        service: FakeAccessibilityService,
        planner: RuntimeHarnessPlanner,
        clock: RuntimeHarnessClock,
    ): RuntimeContractHarness = RuntimeContractHarness(
        service = service,
        planner = planner,
        clock = clock,
        launchablePackages = setOf("example.app"),
        packagePolicy = PackagePolicy(allowedPackages = mutableSetOf("example.app"), primaryPackage = "example.app"),
    )

    private fun disappearedPlan(): TaskPlan = TaskPlan(
        summary = "dismiss",
        targetAppHint = "example.app",
        goal = GoalContext("dismiss"),
        milestones = listOf(TaskMilestone(
            "m1",
            "dismiss target",
            listOf(UiPredicate(UiPredicateKind.ELEMENT_DISAPPEARED, targetHint = "Dismiss", description = "dialog is gone")),
        )),
    )

    private fun node(id: Int, text: String, packageName: String = "example.app") =
        UiNodeSnapshot(id, text, "", "Button", true, false, "0,0,100,40", packageName = packageName)

    private class FakeAccessibilityService(
        initial: Observation,
        private val executeSucceeds: Boolean = true,
        observationQueue: List<Observation> = emptyList(),
    ) : RuntimeHarnessAccessibilityService {
        var current = initial
        val recoveryActions = mutableListOf<RecoveryAction>()
        var executeCount = 0
        private val queuedObservations = ArrayDeque(observationQueue)

        override suspend fun observe(): Observation {
            if (queuedObservations.isNotEmpty()) current = queuedObservations.removeFirst()
            return current
        }

        override suspend fun executeDetailed(action: AgentAction, observation: Observation): ActionExecutionResult {
            executeCount++
            if (!executeSucceeds) return ActionExecutionResult(false, "execution_failed", "fake failure")
            if (action is AgentAction.ClickNode) current = Observation("example.app", emptyList())
            return ActionExecutionResult(true, "executed", "fake execution")
        }

        override suspend fun executeRecovery(action: RecoveryAction, observation: Observation): ActionExecutionResult {
            recoveryActions += action
            return ActionExecutionResult(true, "recovered", "fake recovery")
        }
    }

    private class FakePlanner(private val next: () -> AgentAction) : RuntimeHarnessPlanner {
        override suspend fun nextAction(observation: Observation, milestone: TaskMilestone, history: List<ActionRecord>): AgentAction = next()
    }

    private class FakeClock : RuntimeHarnessClock {
        var calls = 0
        override suspend fun afterAction(before: Observation, action: AgentAction, observe: suspend () -> Observation): Observation {
            calls++
            return observe()
        }
    }

    private class WindowDismissService(initial: Observation) : RuntimeHarnessAccessibilityService {
        private var current = initial

        override suspend fun observe(): Observation = current

        override suspend fun executeDetailed(action: AgentAction, observation: Observation): ActionExecutionResult {
            current = Observation(
                "example.app",
                listOf(UiNodeSnapshot(2, "Ready", "", "TextView", false, false, "0,50,100,80", packageName = "example.app", windowId = 7)),
                windowIds = setOf(7),
            )
            return ActionExecutionResult(true, "executed", "window target dismissed")
        }

        override suspend fun executeRecovery(action: RecoveryAction, observation: Observation): ActionExecutionResult =
            ActionExecutionResult(true, "recovered", action.name)
    }
}
