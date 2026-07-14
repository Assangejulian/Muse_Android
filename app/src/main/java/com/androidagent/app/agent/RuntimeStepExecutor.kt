package com.androidagent.app.agent

/** Shared deterministic preflight used by production Runtime and the contract harness. */
data class RuntimeStepPreflight(
    val executionObservation: Observation,
    val stale: Boolean,
    val guarded: GuardResult,
    val action: AgentAction?,
    val safetyFailure: String? = null,
    val repeatedReason: String? = null,
    val repeatedRecoveryReason: RecoveryReason? = null,
)

object RuntimeStepExecutor {
    fun preflight(
        guard: ToolGuard,
        ledger: RunLedger,
        proposed: AgentAction,
        planningObservation: Observation,
        executionObservation: Observation,
        milestone: TaskMilestone,
        packagePolicy: PackagePolicy,
        launchablePackages: Set<String>,
        goal: GoalContext,
    ): RuntimeStepPreflight {
        if (executionObservation.observationId != planningObservation.observationId) {
            return RuntimeStepPreflight(
                executionObservation = executionObservation,
                stale = true,
                guarded = GuardResult(null, "screen changed before tool dispatch; re-observe before acting"),
                action = null,
            )
        }
        val guarded = guard.normalizeAndValidate(proposed, executionObservation, milestone)
        val action = guarded.action ?: guarded.shortCircuit?.let { proposed }
        if (action == null) {
            return RuntimeStepPreflight(executionObservation, false, guarded, null)
        }
        val safetyFailure = SafetyGuard.validate(
            action,
            executionObservation,
            packagePolicy,
            launchablePackages,
            goal,
        ).exceptionOrNull()?.message
        if (safetyFailure != null) {
            return RuntimeStepPreflight(executionObservation, false, guarded, action, safetyFailure = safetyFailure)
        }
        val repeatedReason = ledger.blockRepeated(action, executionObservation)
        return RuntimeStepPreflight(
            executionObservation = executionObservation,
            stale = false,
            guarded = guarded,
            action = action,
            repeatedReason = repeatedReason,
            repeatedRecoveryReason = repeatedReason?.let { ledger.repeatedRecoveryReason(action, executionObservation) },
        )
    }
}

data class RuntimeStepSettleResult(
    val state: DispatchResultState,
    val observation: Observation,
    val detail: String,
)

data class RuntimeStepRecoveryResult(
    val success: Boolean,
    val observation: Observation,
    val detail: String,
)

interface RuntimeStepDriver {
    suspend fun executeDetailed(action: AgentAction, observation: Observation): ActionExecutionResult
    suspend fun settle(before: Observation, action: AgentAction): RuntimeStepSettleResult
    suspend fun executeRecovery(decision: RecoveryDecision, observation: Observation): RuntimeStepRecoveryResult
}

enum class RuntimeStepStatus {
    STALE,
    BLOCKED,
    EXECUTION_FAILED,
    OBSERVATION_ONLY,
    PROGRESS,
    NO_PROGRESS,
    MILESTONE_COMPLETE,
    RESULT_UNKNOWN,
    REPLAN_REQUIRED,
    ABORTED,
}

data class RuntimeStepEngineEvent(val phase: String, val detail: String = "")

data class RuntimeStepRequest(
    val step: Int,
    val proposed: AgentAction,
    val planningObservation: Observation,
    val executionObservation: Observation,
    val plan: TaskPlan,
    val milestone: TaskMilestone,
    val guard: ToolGuard,
    val ledger: RunLedger,
    val bindings: PredicateBindingStore,
    val recoveryPolicy: RecoveryPolicy,
    val packagePolicy: PackagePolicy,
    val launchablePackages: Set<String>,
    val goal: GoalContext,
    val targetPackage: String?,
    val evidenceCounters: StopGateEvidenceCounters,
    val runId: String? = null,
)

data class RuntimeStepEngineResult(
    val status: RuntimeStepStatus,
    val action: AgentAction?,
    val before: Observation,
    val after: Observation,
    val execution: ActionExecutionResult? = null,
    val evidence: PredicateEvidence? = null,
    val reason: String,
    val dispatchResultState: DispatchResultState? = null,
    val recoveryDecisions: List<RecoveryDecision> = emptyList(),
    val events: List<RuntimeStepEngineEvent> = emptyList(),
) {
    val completed: Boolean get() = status == RuntimeStepStatus.MILESTONE_COMPLETE
    val needsReplan: Boolean get() = status == RuntimeStepStatus.REPLAN_REQUIRED
}

/**
 * One production Runtime step. All binding writes happen only after local
 * guards pass and either an observation-only action is accepted or Android
 * accepts a mutating action.
 */
class RuntimeStepEngine(private val driver: RuntimeStepDriver) {
    suspend fun execute(request: RuntimeStepRequest): RuntimeStepEngineResult {
        val events = mutableListOf<RuntimeStepEngineEvent>()
        val recoveryDecisions = mutableListOf<RecoveryDecision>()
        val preflight = RuntimeStepExecutor.preflight(
            guard = request.guard,
            ledger = request.ledger,
            proposed = request.proposed,
            planningObservation = request.planningObservation,
            executionObservation = request.executionObservation,
            milestone = request.milestone,
            packagePolicy = request.packagePolicy,
            launchablePackages = request.launchablePackages,
            goal = request.goal,
        )
        if (preflight.stale) {
            events += RuntimeStepEngineEvent("stale_observation", preflight.guarded.rejection.orEmpty())
            return recoverOnce(
                request,
                RecoveryReason.SCREEN_UNCHANGED,
                request.proposed,
                request.executionObservation,
                RuntimeStepStatus.STALE,
                events,
                recoveryDecisions,
            )
        }

        events += RuntimeStepEngineEvent(
            "tool_guard",
            if (preflight.guarded.action != null || preflight.guarded.shortCircuit != null) "allowed" else "rejected",
        )
        val action = preflight.action
        if (action == null) {
            return recoverOnce(
                request,
                classifyFailure(request.proposed, preflight.guarded.rejection.orEmpty()),
                request.proposed,
                request.executionObservation,
                RuntimeStepStatus.BLOCKED,
                events,
                recoveryDecisions,
                preflight.guarded.rejection ?: "tool guard rejected the action",
            )
        }

        events += RuntimeStepEngineEvent("safety_guard", if (preflight.safetyFailure == null) "allowed" else "rejected")
        if (preflight.safetyFailure != null) {
            return recoverOnce(
                request,
                classifyFailure(action, preflight.safetyFailure),
                action,
                request.executionObservation,
                RuntimeStepStatus.BLOCKED,
                events,
                recoveryDecisions,
                preflight.safetyFailure,
            )
        }

        events += RuntimeStepEngineEvent("duplicate", if (preflight.repeatedReason == null) "allowed" else "rejected")
        if (preflight.repeatedReason != null) {
            return recoverOnce(
                request,
                preflight.repeatedRecoveryReason ?: RecoveryReason.REPEATED_ACTION,
                action,
                request.executionObservation,
                RuntimeStepStatus.BLOCKED,
                events,
                recoveryDecisions,
                preflight.repeatedReason,
            )
        }

        val preparation = request.bindings.prepareActionBinding(
            request.milestone,
            action,
            request.executionObservation,
            request.runId,
        )
        events += RuntimeStepEngineEvent("prepare_binding", if (preparation.prepared) "prepared" else "rejected")
        if (!preparation.prepared) {
            val reason = if (preparation.reason.contains("ambiguous", true)) {
                RecoveryReason.AMBIGUOUS_TARGET
            } else {
                RecoveryReason.TARGET_MISSING
            }
            return recoverOnce(
                request,
                reason,
                action,
                request.executionObservation,
                RuntimeStepStatus.BLOCKED,
                events,
                recoveryDecisions,
                preparation.reason,
            )
        }

        if (preflight.guarded.shortCircuit != null || action is AgentAction.BindPredicate) {
            val origin = if (preflight.guarded.shortCircuit?.status == "already_satisfied") {
                BindingOrigin.ALREADY_SATISFIED
            } else {
                BindingOrigin.OBSERVATION_ONLY
            }
            if (!request.bindings.commitObservation(preparation, origin)) {
                return aborted(request, action, "predicate binding transaction conflict", events, recoveryDecisions)
            }
            events += RuntimeStepEngineEvent("commit", origin.name)
            request.evidenceCounters.successfulObservationActions++
            val evidence = evaluate(request, request.executionObservation)
            val execution = preflight.guarded.shortCircuit
                ?: ActionExecutionResult(true, "bound", "predicate target bound without a side effect")
            val status = completeOrObserve(request, evidence, request.executionObservation, action, execution.detail, events)
            return RuntimeStepEngineResult(
                status = status,
                action = action,
                before = request.executionObservation,
                after = request.executionObservation,
                execution = execution,
                evidence = evidence,
                reason = evidence.details.joinToString(" | ").ifBlank { execution.detail },
                events = events,
            )
        }

        val actionKey = request.ledger.actionKey(action, request.executionObservation)
        val baseline = MilestoneEvaluator.strongPostconditionBaseline(
            milestone = request.milestone,
            plan = request.plan,
            observation = request.executionObservation,
            targetPackage = request.targetPackage,
            bindings = request.bindings,
            runId = request.runId,
        )
        events += RuntimeStepEngineEvent("execute", TraceSanitizer.actionType(action))
        val execution = driver.executeDetailed(action, request.executionObservation)
        if (!execution.success) {
            events += RuntimeStepEngineEvent("execute_failed", execution.status)
            return recoverOnce(
                request,
                classifyFailure(action, execution.status + " " + execution.detail),
                action,
                request.executionObservation,
                RuntimeStepStatus.EXECUTION_FAILED,
                events,
                recoveryDecisions,
                execution.detail.ifBlank { execution.status },
                execution,
                DispatchResultState.FAILED,
            )
        }
        if (!request.bindings.commitMutation(
                preparation,
                actionKey,
                request.executionObservation.observationId,
                baseline,
            )) {
            return aborted(request, action, "predicate binding commit failed after successful dispatch", events, recoveryDecisions)
        }
        events += RuntimeStepEngineEvent("mark_dispatched", actionKey)
        events += RuntimeStepEngineEvent("commit", "MUTATING_ACTION")
        request.evidenceCounters.successfulMutatingActions++

        val settled = driver.settle(request.executionObservation, action)
        events += RuntimeStepEngineEvent("wait", "${settled.state}:${settled.detail}")
        return if (settled.state != DispatchResultState.CONFIRMED) {
            request.bindings.markResultUnknown(preparation, actionKey)
            request.ledger.recordDispatch(action, request.executionObservation, DispatchResultState.RESULT_UNKNOWN)
            resolveUnknown(request, preparation, action, actionKey, execution, settled, events, recoveryDecisions)
        } else {
            request.bindings.markResultObserved(preparation, actionKey)
            request.ledger.recordDispatch(action, request.executionObservation, DispatchResultState.CONFIRMED)
            evaluateConfirmed(request, action, execution, settled.observation, events, recoveryDecisions)
        }
    }

    private suspend fun resolveUnknown(
        request: RuntimeStepRequest,
        preparation: BindingPreparation,
        action: AgentAction,
        actionKey: String,
        execution: ActionExecutionResult,
        settled: RuntimeStepSettleResult,
        events: MutableList<RuntimeStepEngineEvent>,
        recoveryDecisions: MutableList<RecoveryDecision>,
    ): RuntimeStepEngineResult {
        var observation = settled.observation
        repeat(3) {
            val decision = request.recoveryPolicy.decide(
                RecoveryContext(
                    expectedPackage = request.targetPackage,
                    currentPackage = observation.packageName,
                    currentMilestoneId = request.milestone.id,
                    currentMilestoneKind = request.milestone.kind,
                    failedAction = action,
                    reason = RecoveryReason.RESULT_UNKNOWN,
                ),
            )
            recoveryDecisions += decision
            val recovery = driver.executeRecovery(decision, observation)
            events += RuntimeStepEngineEvent("recover", "${decision.action}:${recovery.detail}")
            observation = recovery.observation
            val evidence = evaluate(request, observation)
            events += RuntimeStepEngineEvent("evaluate", "result_unknown:${if (evidence.proven) "proven" else "unknown"}")
            if (evidence.proven) {
                request.bindings.markResultObserved(preparation, actionKey)
                completeMilestone(request, evidence, observation, action, events)
                return RuntimeStepEngineResult(
                    RuntimeStepStatus.MILESTONE_COMPLETE,
                    action,
                    request.executionObservation,
                    observation,
                    execution,
                    evidence,
                    evidence.details.joinToString(" | "),
                    DispatchResultState.RESULT_UNKNOWN,
                    recoveryDecisions,
                    events,
                )
            }
            if (decision.action == RecoveryAction.REPLAN) {
                return RuntimeStepEngineResult(
                    RuntimeStepStatus.REPLAN_REQUIRED,
                    action,
                    request.executionObservation,
                    observation,
                    execution,
                    evidence,
                    "dispatch result remains unknown after re-observe and short wait",
                    DispatchResultState.RESULT_UNKNOWN,
                    recoveryDecisions,
                    events,
                )
            }
        }
        return RuntimeStepEngineResult(
            RuntimeStepStatus.RESULT_UNKNOWN,
            action,
            request.executionObservation,
            observation,
            execution,
            reason = "dispatch result remains unknown",
            dispatchResultState = DispatchResultState.RESULT_UNKNOWN,
            recoveryDecisions = recoveryDecisions,
            events = events,
        )
    }

    private fun evaluateConfirmed(
        request: RuntimeStepRequest,
        action: AgentAction,
        execution: ActionExecutionResult,
        after: Observation,
        events: MutableList<RuntimeStepEngineEvent>,
        recoveryDecisions: List<RecoveryDecision>,
    ): RuntimeStepEngineResult {
        request.ledger.observe(after)
        val evidence = evaluate(request, after)
        events += RuntimeStepEngineEvent("evaluate", if (evidence.proven) "proven" else "unknown")
        val changed = request.executionObservation.observationId != after.observationId
        val status = if (evidence.proven) {
            completeMilestone(request, evidence, after, action, events)
            RuntimeStepStatus.MILESTONE_COMPLETE
        } else if (changed) {
            request.recoveryPolicy.resetFailures(request.milestone.id)
            RuntimeStepStatus.PROGRESS
        } else {
            RuntimeStepStatus.NO_PROGRESS
        }
        val reason = when {
            evidence.proven -> evidence.details.joinToString(" | ")
            changed -> TraceSanitizer.observationDelta(request.executionObservation, after)
            else -> "No stable UI state change"
        }
        request.ledger.record(
            StepTrace(
                request.milestone.id,
                request.executionObservation.observationId,
                TraceSanitizer.action(action),
                after.observationId,
                status.toJudgement(),
                reason,
            ),
        )
        return RuntimeStepEngineResult(
            status,
            action,
            request.executionObservation,
            after,
            execution,
            evidence,
            reason,
            DispatchResultState.CONFIRMED,
            recoveryDecisions,
            events,
        )
    }

    private fun completeOrObserve(
        request: RuntimeStepRequest,
        evidence: PredicateEvidence,
        observation: Observation,
        action: AgentAction,
        fallbackReason: String,
        events: MutableList<RuntimeStepEngineEvent>,
    ): RuntimeStepStatus {
        return if (evidence.proven) {
            completeMilestone(request, evidence, observation, action, events)
            RuntimeStepStatus.MILESTONE_COMPLETE
        } else {
            request.ledger.record(
                StepTrace(
                    request.milestone.id,
                    observation.observationId,
                    TraceSanitizer.action(action),
                    observation.observationId,
                    TransitionJudgement.PROGRESS,
                    fallbackReason,
                ),
            )
            RuntimeStepStatus.OBSERVATION_ONLY
        }
    }

    private fun completeMilestone(
        request: RuntimeStepRequest,
        evidence: PredicateEvidence,
        observation: Observation,
        action: AgentAction,
        events: MutableList<RuntimeStepEngineEvent>,
    ) {
        val proof = evidence.details.joinToString(" | ")
        request.evidenceCounters.deterministicEvidenceCount++
        request.evidenceCounters.verifiedMilestones++
        request.bindings.markVerified(request.milestone.id)
        request.ledger.record(
            StepTrace(
                request.milestone.id,
                request.executionObservation.observationId,
                TraceSanitizer.action(action),
                observation.observationId,
                TransitionJudgement.MILESTONE_COMPLETE,
                proof,
            ),
        )
        request.ledger.advance(proof)
        request.recoveryPolicy.resetFailures(request.milestone.id)
        events += RuntimeStepEngineEvent("stop_gate", "local evidence")
    }

    private fun evaluate(request: RuntimeStepRequest, observation: Observation): PredicateEvidence =
        MilestoneEvaluator.evaluate(
            request.milestone,
            request.plan,
            observation,
            request.targetPackage,
            request.bindings,
            runId = request.runId,
        )

    private suspend fun recoverOnce(
        request: RuntimeStepRequest,
        reason: RecoveryReason,
        action: AgentAction?,
        observation: Observation,
        fallbackStatus: RuntimeStepStatus,
        events: MutableList<RuntimeStepEngineEvent>,
        decisions: MutableList<RecoveryDecision>,
        detail: String = reason.name,
        execution: ActionExecutionResult? = null,
        dispatchResultState: DispatchResultState? = null,
    ): RuntimeStepEngineResult {
        val decision = request.recoveryPolicy.decide(
            RecoveryContext(
                expectedPackage = request.targetPackage,
                currentPackage = observation.packageName,
                currentMilestoneId = request.milestone.id,
                currentMilestoneKind = request.milestone.kind,
                failedAction = action,
                reason = reason,
            ),
        )
        decisions += decision
        val recovery = driver.executeRecovery(decision, observation)
        events += RuntimeStepEngineEvent("recover", "${decision.action}:${recovery.detail}")
        val status = when {
            decision.action == RecoveryAction.REPLAN -> RuntimeStepStatus.REPLAN_REQUIRED
            decision.action == RecoveryAction.ABORT || !recovery.success -> RuntimeStepStatus.ABORTED
            else -> fallbackStatus
        }
        request.ledger.record(
            StepTrace(
                request.milestone.id,
                request.planningObservation.observationId,
                action?.let(TraceSanitizer::action) ?: "none",
                recovery.observation.observationId,
                TransitionJudgement.NO_PROGRESS,
                detail,
            ),
        )
        return RuntimeStepEngineResult(
            status,
            action,
            request.executionObservation,
            recovery.observation,
            execution,
            reason = detail,
            dispatchResultState = dispatchResultState,
            recoveryDecisions = decisions,
            events = events,
        )
    }

    private fun aborted(
        request: RuntimeStepRequest,
        action: AgentAction?,
        reason: String,
        events: List<RuntimeStepEngineEvent>,
        decisions: List<RecoveryDecision>,
    ): RuntimeStepEngineResult = RuntimeStepEngineResult(
        RuntimeStepStatus.ABORTED,
        action,
        request.executionObservation,
        request.executionObservation,
        reason = reason,
        recoveryDecisions = decisions,
        events = events,
    )

    private fun classifyFailure(action: AgentAction, detail: String): RecoveryReason {
        val normalized = detail.lowercase()
        return when {
            normalized.contains("result is unknown") -> RecoveryReason.RESULT_UNKNOWN
            normalized.contains("ambiguous") -> RecoveryReason.AMBIGUOUS_TARGET
            normalized.contains("missing") || normalized.contains("selector") -> RecoveryReason.TARGET_MISSING
            action is AgentAction.InputText || action is AgentAction.SubmitInput || normalized.contains("input") || normalized.contains("text_") -> RecoveryReason.INPUT_FAILED
            normalized.contains("package") -> RecoveryReason.WRONG_PACKAGE
            normalized.contains("network") || normalized.contains("http ") -> RecoveryReason.NETWORK_ERROR
            action is AgentAction.LaunchApp -> RecoveryReason.APP_NOT_RESPONDING
            else -> RecoveryReason.TARGET_MISSING
        }
    }

    private fun RuntimeStepStatus.toJudgement(): TransitionJudgement = when (this) {
        RuntimeStepStatus.MILESTONE_COMPLETE -> TransitionJudgement.MILESTONE_COMPLETE
        RuntimeStepStatus.PROGRESS, RuntimeStepStatus.OBSERVATION_ONLY -> TransitionJudgement.PROGRESS
        else -> TransitionJudgement.NO_PROGRESS
    }
}
