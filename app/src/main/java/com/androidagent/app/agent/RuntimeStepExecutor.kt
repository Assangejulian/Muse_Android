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
    val resolvedTarget: ResolvedActionTarget? = null,
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
            guarded.resolvedTarget,
        ).exceptionOrNull()?.message
        if (safetyFailure != null) {
            return RuntimeStepPreflight(executionObservation, false, guarded, action, safetyFailure = safetyFailure)
        }
        return RuntimeStepPreflight(
            executionObservation = executionObservation,
            stale = false,
            guarded = guarded,
            action = action,
            resolvedTarget = guarded.resolvedTarget,
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
    suspend fun executeDetailed(
        action: AgentAction,
        observation: Observation,
        resolvedTarget: ResolvedActionTarget?,
    ): ActionExecutionResult = executeDetailed(action, observation)
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
    val sideEffects: RunScopedSideEffectLedger = RunScopedSideEffectLedger(runId),
    val preDispatchSnapshots: PreDispatchEvidenceStore = PreDispatchEvidenceStore(),
    val screenshotFingerprint: String? = null,
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
    val resolvedTarget: ResolvedActionTarget? = null,
    val inputGeneration: Int? = null,
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

        val preparation = request.bindings.prepareActionBinding(
            request.milestone,
            action,
            request.executionObservation,
            request.runId,
            preflight.resolvedTarget,
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
                resolvedTarget = preflight.resolvedTarget,
            )
        }

        val inputGenerationReservation = if (action is AgentAction.InputText) {
            preflight.resolvedTarget?.let { request.sideEffects.prepareInputGeneration(action, it) }
        } else null
        val inputGeneration = when (action) {
            is AgentAction.InputText -> inputGenerationReservation?.generation
            is AgentAction.SubmitInput -> preflight.resolvedTarget?.let(request.sideEffects::currentInputGeneration)
            else -> null
        }
        val sideEffectIdentity = SideEffectIdentityFactory.create(
            action = action,
            observation = request.executionObservation,
            screenshotFingerprint = request.screenshotFingerprint,
            resolvedTarget = preflight.resolvedTarget,
            inputGeneration = inputGeneration,
        )
        if (SideEffectIdentityFactory.requiresIdentity(action) && sideEffectIdentity == null) {
            return recoverOnce(
                request,
                RecoveryReason.TARGET_MISSING,
                action,
                request.executionObservation,
                RuntimeStepStatus.BLOCKED,
                events,
                recoveryDecisions,
                "mutating action has no canonical side-effect identity",
                resolvedTarget = preflight.resolvedTarget,
                inputGeneration = inputGeneration,
            )
        }
        val repeatedReason = request.ledger.blockRepeated(
            action,
            request.executionObservation,
            preflight.resolvedTarget,
            sideEffectIdentity,
        )
        events += RuntimeStepEngineEvent("duplicate", if (repeatedReason == null) "allowed" else "rejected")
        if (repeatedReason != null) {
            return recoverOnce(
                request,
                request.ledger.repeatedRecoveryReason(
                    action,
                    request.executionObservation,
                    preflight.resolvedTarget,
                    sideEffectIdentity,
                ) ?: RecoveryReason.REPEATED_ACTION,
                action,
                request.executionObservation,
                RuntimeStepStatus.BLOCKED,
                events,
                recoveryDecisions,
                repeatedReason,
                resolvedTarget = preflight.resolvedTarget,
                inputGeneration = inputGeneration,
            )
        }
        sideEffectIdentity?.let { identity ->
            val check = request.sideEffects.check(identity)
            if (!check.allowed) {
                return recoverOnce(
                    request,
                    RecoveryReason.REPEATED_ACTION,
                    action,
                    request.executionObservation,
                    RuntimeStepStatus.BLOCKED,
                    events,
                    recoveryDecisions,
                    check.reason.orEmpty(),
                    resolvedTarget = preflight.resolvedTarget,
                    inputGeneration = inputGeneration,
                )
            }
        }

        val actionKey = request.ledger.actionKey(
            action,
            request.executionObservation,
            preflight.resolvedTarget,
            sideEffectIdentity,
        )
        val baselineTruth = MilestoneEvaluator.strongPostconditionTruthBaseline(
            milestone = request.milestone,
            plan = request.plan,
            observation = request.executionObservation,
            targetPackage = request.targetPackage,
            bindings = request.bindings,
            runId = request.runId,
            preDispatchSnapshots = request.preDispatchSnapshots,
        )
        val dispatchSequence = sideEffectIdentity?.let { request.sideEffects.nextDispatchSequence() }
        val preDispatchSnapshot = sideEffectIdentity?.let { identity ->
            request.preDispatchSnapshots.capture(
                observation = request.executionObservation,
                identity = identity,
                dispatchSequence = dispatchSequence ?: 0L,
            )
        }
        events += RuntimeStepEngineEvent("execute", TraceSanitizer.actionType(action))
        val execution = driver.executeDetailed(action, request.executionObservation, preflight.resolvedTarget)
        if (!execution.success) {
            request.preDispatchSnapshots.remove(preDispatchSnapshot?.snapshotId)
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
                preflight.resolvedTarget,
                inputGeneration,
            )
        }
        if (sideEffectIdentity != null && !request.sideEffects.markUnknown(
                identity = sideEffectIdentity,
                dispatchObservationId = request.executionObservation.observationId,
                dispatchSequence = dispatchSequence ?: request.sideEffects.nextDispatchSequence(),
                preDispatchSnapshotId = preDispatchSnapshot?.snapshotId,
                inputGenerationReservation = inputGenerationReservation,
            )) {
            return aborted(
                request,
                action,
                "side effect identity was concurrently dispatched",
                events,
                recoveryDecisions,
                preflight.resolvedTarget,
                inputGeneration,
            )
        }
        if (!request.bindings.commitMutation(
                preparation,
                actionKey,
                request.executionObservation.observationId,
                baselineTruth.mapValues { (_, truth) -> truth == PredicateTruth.PROVEN },
                preDispatchSnapshotId = preDispatchSnapshot?.snapshotId,
                strongPostconditionTruthBefore = baselineTruth,
            )) {
            return aborted(
                request,
                action,
                "predicate binding commit failed after successful dispatch",
                events,
                recoveryDecisions,
                preflight.resolvedTarget,
                inputGeneration,
            )
        }
        events += RuntimeStepEngineEvent("mark_dispatched", actionKey)
        events += RuntimeStepEngineEvent("commit", "MUTATING_ACTION")
        request.evidenceCounters.successfulMutatingActions++

        val settled = driver.settle(request.executionObservation, action)
        events += RuntimeStepEngineEvent("wait", "${settled.state}:${settled.detail}")
        return if (settled.state != DispatchResultState.CONFIRMED) {
            request.bindings.markResultUnknown(preparation, actionKey)
            request.ledger.recordDispatch(
                action,
                request.executionObservation,
                DispatchResultState.RESULT_UNKNOWN,
                preflight.resolvedTarget,
                sideEffectIdentity,
            )
            resolveUnknown(
                request,
                preparation,
                action,
                actionKey,
                execution,
                settled,
                sideEffectIdentity,
                preflight.resolvedTarget,
                inputGeneration,
                events,
                recoveryDecisions,
            )
        } else {
            sideEffectIdentity?.let { request.sideEffects.markConfirmed(it) }
            request.bindings.markResultObserved(preparation, actionKey)
            request.ledger.recordDispatch(
                action,
                request.executionObservation,
                DispatchResultState.CONFIRMED,
                preflight.resolvedTarget,
                sideEffectIdentity,
            )
            evaluateConfirmed(
                request,
                action,
                execution,
                settled.observation,
                preflight.resolvedTarget,
                inputGeneration,
                events,
                recoveryDecisions,
            )
        }
    }

    private suspend fun resolveUnknown(
        request: RuntimeStepRequest,
        preparation: BindingPreparation,
        action: AgentAction,
        actionKey: String,
        execution: ActionExecutionResult,
        settled: RuntimeStepSettleResult,
        sideEffectIdentity: SideEffectIdentity?,
        resolvedTarget: ResolvedActionTarget?,
        inputGeneration: Int?,
        events: MutableList<RuntimeStepEngineEvent>,
        recoveryDecisions: MutableList<RecoveryDecision>,
    ): RuntimeStepEngineResult {
        var observation = settled.observation
        fun result(status: RuntimeStepStatus, reason: String): RuntimeStepEngineResult = RuntimeStepEngineResult(
            status = status,
            action = action,
            before = request.executionObservation,
            after = observation,
            execution = execution,
            reason = reason,
            dispatchResultState = DispatchResultState.RESULT_UNKNOWN,
            recoveryDecisions = recoveryDecisions,
            events = events,
            resolvedTarget = resolvedTarget,
            inputGeneration = inputGeneration,
        )

        if (request.recoveryPolicy.budgetExhausted()) {
            return result(RuntimeStepStatus.ABORTED, "recovery budget exhausted while resolving unknown dispatch")
        }
        var evidence: PredicateEvidence
        for (attempt in 0 until 3) {
            if (request.recoveryPolicy.budgetExhausted()) {
                return result(RuntimeStepStatus.ABORTED, "recovery budget exhausted while resolving unknown dispatch")
            }
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
            if (decision.action == RecoveryAction.ABORT) {
                return result(RuntimeStepStatus.ABORTED, decision.detail)
            }
            val recovery = driver.executeRecovery(decision, observation)
            events += RuntimeStepEngineEvent("recover", "${decision.action}:${recovery.detail}")
            observation = recovery.observation
            evidence = evaluate(request, observation)
            events += RuntimeStepEngineEvent("evaluate", "result_unknown:${if (evidence.proven) "proven" else "unknown"}")
            if (evidence.proven) {
                sideEffectIdentity?.let { request.sideEffects.markConfirmed(it) }
                request.bindings.markResultObserved(preparation, actionKey)
                completeMilestone(request, evidence, observation, action, events)
                return result(RuntimeStepStatus.MILESTONE_COMPLETE, evidence.details.joinToString(" | "))
            }
            if (evidence.replanRequired) {
                return result(RuntimeStepStatus.REPLAN_REQUIRED, MilestoneEvaluator.BOUND_WINDOW_GONE_REPLAN_REASON)
            }
            if (decision.action == RecoveryAction.REPLAN) {
                return result(RuntimeStepStatus.REPLAN_REQUIRED, "dispatch result remains unknown; replan required")
            }
            if (!recovery.success) {
                if (decision.action == RecoveryAction.WAIT) {
                    if (request.recoveryPolicy.budgetExhausted()) {
                        return result(RuntimeStepStatus.ABORTED, "recovery budget exhausted while resolving unknown dispatch")
                    }
                    continue
                }
                return result(RuntimeStepStatus.ABORTED, "${decision.action.name.lowercase()} recovery failed: ${recovery.detail}")
            }
            if (request.recoveryPolicy.budgetExhausted()) {
                return result(RuntimeStepStatus.ABORTED, "recovery budget exhausted while resolving unknown dispatch")
            }
        }
        return result(RuntimeStepStatus.ABORTED, "unknown dispatch recovery attempts exhausted")
    }

    private fun evaluateConfirmed(
        request: RuntimeStepRequest,
        action: AgentAction,
        execution: ActionExecutionResult,
        after: Observation,
        resolvedTarget: ResolvedActionTarget?,
        inputGeneration: Int?,
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
        } else if (evidence.replanRequired) {
            RuntimeStepStatus.REPLAN_REQUIRED
        } else if (changed) {
            request.recoveryPolicy.resetFailures(request.milestone.id)
            RuntimeStepStatus.PROGRESS
        } else {
            RuntimeStepStatus.NO_PROGRESS
        }
        val reason = when {
            evidence.proven -> evidence.details.joinToString(" | ")
            evidence.replanRequired -> MilestoneEvaluator.BOUND_WINDOW_GONE_REPLAN_REASON
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
            resolvedTarget,
            inputGeneration,
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
            preDispatchSnapshots = request.preDispatchSnapshots,
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
        resolvedTarget: ResolvedActionTarget? = null,
        inputGeneration: Int? = null,
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
            resolvedTarget = resolvedTarget,
            inputGeneration = inputGeneration,
        )
    }

    private fun aborted(
        request: RuntimeStepRequest,
        action: AgentAction?,
        reason: String,
        events: List<RuntimeStepEngineEvent>,
        decisions: List<RecoveryDecision>,
        resolvedTarget: ResolvedActionTarget? = null,
        inputGeneration: Int? = null,
    ): RuntimeStepEngineResult = RuntimeStepEngineResult(
        RuntimeStepStatus.ABORTED,
        action,
        request.executionObservation,
        request.executionObservation,
        reason = reason,
        recoveryDecisions = decisions,
        events = events,
        resolvedTarget = resolvedTarget,
        inputGeneration = inputGeneration,
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
