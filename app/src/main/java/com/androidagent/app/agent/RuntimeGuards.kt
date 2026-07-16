package com.androidagent.app.agent

enum class PredicateTruth { PROVEN, REFUTED, UNKNOWN }

internal sealed interface HistoricalIdentityResolution {
    data class Found(val node: UiNodeSnapshot) : HistoricalIdentityResolution
    data object Missing : HistoricalIdentityResolution
    data object Ambiguous : HistoricalIdentityResolution
    data object IdentityInvalidated : HistoricalIdentityResolution
}

data class PredicateEvidence(
    val proven: Boolean,
    val details: List<String>,
    val replanRequired: Boolean = false,
    val truths: Map<String, PredicateTruth> = emptyMap(),
) {
    fun truthFor(predicateId: String): PredicateTruth = truths[predicateId] ?: PredicateTruth.UNKNOWN
}

enum class BindingLifecycle { PREPARED, DISPATCHED, COMMITTED, VERIFIED, INVALIDATED, REBIND_REQUIRED }

enum class BindingOrigin { OBSERVATION_ONLY, ALREADY_SATISFIED, MUTATING_ACTION }

enum class BindingResultState { BOUND_ONLY, DISPATCHED, RESULT_UNKNOWN, RESULT_OBSERVED, VERIFIED }

enum class DispatchResultState { CONFIRMED, RESULT_UNKNOWN, FAILED }

/** Local evidence counters used by the Stop Gate; tool volume is not evidence. */
data class StopGateEvidenceCounters(
    var successfulMutatingActions: Int = 0,
    var successfulObservationActions: Int = 0,
    var verifiedMilestones: Int = 0,
    var deterministicEvidenceCount: Int = 0,
) {
    fun hasLocalEvidence(): Boolean = deterministicEvidenceCount > 0 || verifiedMilestones > 0
}

data class PredicateBinding(
    val milestoneId: String,
    val predicateIndex: Int,
    val predicateId: String?,
    val boundSelector: ElementSelector,
    val identity: BoundElementIdentity,
    val boundObservationId: String,
    val boundPackage: String,
    val runId: String? = null,
    val lifecycle: BindingLifecycle = BindingLifecycle.COMMITTED,
    val origin: BindingOrigin = BindingOrigin.OBSERVATION_ONLY,
    val dispatchedActionKey: String? = null,
    val dispatchedObservationId: String? = null,
    val dispatchSequence: Long? = null,
    val resultState: BindingResultState = BindingResultState.BOUND_ONLY,
    val strongPostconditionsBefore: Map<String, Boolean> = emptyMap(),
    val preDispatchSnapshotId: String? = null,
    /** Tri-state baseline; the legacy Boolean map remains for source compatibility. */
    val strongPostconditionTruthBefore: Map<String, PredicateTruth> = emptyMap(),
)

data class BindingTransitionProposal(
    val existingBinding: PredicateBinding?,
    val requestedLifecycle: BindingLifecycle,
    val replacementBinding: PredicateBinding?,
    val reason: String,
)

data class ProvisionalPredicateBinding(
    val binding: PredicateBinding,
    val existingBinding: PredicateBinding? = null,
    val requestedLifecycle: BindingLifecycle = BindingLifecycle.COMMITTED,
    val reason: String = "",
) {
    fun asProposal(): BindingTransitionProposal = BindingTransitionProposal(
        existingBinding = existingBinding,
        requestedLifecycle = requestedLifecycle,
        replacementBinding = binding,
        reason = reason,
    )
}

data class BindingResult(
    val bound: Boolean,
    val binding: PredicateBinding? = null,
    val provisional: ProvisionalPredicateBinding? = null,
    val proposal: BindingTransitionProposal? = null,
    val reason: String = "",
)

data class BindingPreparation(
    val prepared: Boolean,
    val transitions: List<BindingTransitionProposal> = emptyList(),
    val reason: String = "",
    val inferredExistingBinding: PredicateBinding? = null,
) {
    val bound: Boolean get() = prepared
    val provisional: List<ProvisionalPredicateBinding> get() = transitions.mapNotNull { proposal ->
        proposal.replacementBinding?.let { replacement ->
            ProvisionalPredicateBinding(
                binding = replacement,
                existingBinding = proposal.existingBinding,
                requestedLifecycle = proposal.requestedLifecycle,
                reason = proposal.reason,
            )
        }
    }
}

/**
 * Runtime-owned binding ledger. Manager plans may contain only target hints;
 * this ledger records the unique node selected from the live observation
 * immediately before the action that may change the screen.
 */
class PredicateBindingStore {
    private val bindings = linkedMapOf<String, PredicateBinding>()
    private var dispatchSequence = 0L

    fun all(): List<PredicateBinding> = bindings.values.toList()

    fun get(milestoneId: String, predicateIndex: Int): PredicateBinding? =
        bindings[key(milestoneId, predicateIndex)]

    fun bind(
        milestone: TaskMilestone,
        predicateIndex: Int,
        node: UiNodeSnapshot,
        observation: Observation,
        runId: String? = null,
    ): BindingResult {
        val result = prepareBinding(milestone, predicateIndex, node, observation, runId)
        val proposal = result.proposal ?: return result
        if (!commitObservation(
                BindingPreparation(true, listOf(proposal)),
                BindingOrigin.OBSERVATION_ONLY,
            )) return BindingResult(false, reason = "predicate binding conflicts with an existing target")
        return result
    }

    /** Creates a candidate without mutating the ledger. */
    fun prepareBinding(
        milestone: TaskMilestone,
        predicateIndex: Int,
        node: UiNodeSnapshot,
        observation: Observation,
        runId: String? = null,
    ): BindingResult {
        val predicate = milestone.successPredicates.getOrNull(predicateIndex)
            ?: return BindingResult(false, reason = "predicate index is out of range")
        predicate.targetHint?.takeIf(String::isNotBlank)?.let { hint ->
            if (!targetMatchesHint(node, hint)) return BindingResult(false, reason = "target conflicts with predicate targetHint")
        }
        predicate.target?.let { selector ->
            val selectorMatches = NodeSelector.matchingNodes(observation, selector)
            if (selectorMatches.size != 1) {
                return BindingResult(false, reason = if (selectorMatches.isEmpty()) "predicate target missing" else "predicate target is ambiguous")
            }
            if (!sameNode(node, selectorMatches.single())) {
                return BindingResult(false, reason = "target conflicts with predicate selector")
            }
        }
        val identity = BoundElementIdentity.from(node)
        val matches = NodeSelector.matchingNodes(observation, identity)
        if (matches.size != 1) return BindingResult(false, reason = if (matches.isEmpty()) "target missing" else "target is ambiguous")
        val predicateId = predicate.predicateId ?: TaskPlanValidator.predicateIdFor(milestone.id, predicateIndex)
        val replacement = PredicateBinding(
            milestoneId = milestone.id,
            predicateIndex = predicateIndex,
            predicateId = predicateId,
            boundSelector = NodeSelector.from(node),
            identity = identity,
            boundObservationId = observation.observationId,
            boundPackage = node.packageName.ifBlank { observation.packageName },
            runId = runId,
            lifecycle = BindingLifecycle.PREPARED,
        )
        bindings[key(milestone.id, predicateIndex)]?.let { existing ->
            if (existing.lifecycle == BindingLifecycle.VERIFIED) {
                return BindingResult(false, reason = "predicate is already verified")
            }
            if (existing.lifecycle == BindingLifecycle.INVALIDATED) {
                return BindingResult(false, reason = "predicate binding was invalidated")
            }
            val resolution = NodeSelector.resolveIdentity(observation, existing.identity)
            return when (resolution) {
                is IdentityResolution.Found -> {
                    if (!sameNode(node, resolution.node)) {
                        BindingResult(false, reason = "predicate binding conflicts with an existing target")
                    } else {
                        proposalResult(existing, existing, BindingLifecycle.COMMITTED, "already_bound")
                    }
                }
                IdentityResolution.WindowRecreated,
                IdentityResolution.IdentityInvalidated,
                -> {
                    if (predicate.kind == UiPredicateKind.ELEMENT_DISAPPEARED) {
                        BindingResult(false, reason = "disappeared predicate cannot be rebound after its window identity changed")
                    } else {
                        proposalResult(existing, replacement, BindingLifecycle.REBIND_REQUIRED, "rebind_required")
                    }
                }
                else -> BindingResult(false, reason = "predicate binding conflicts with an existing target")
            }
        }
        return proposalResult(null, replacement, BindingLifecycle.PREPARED, "prepared")
    }

    /** Compatibility commit for observation-only callers. */
    fun commit(provisional: ProvisionalPredicateBinding): Boolean = commitAll(listOf(provisional))

    /** Compatibility commit for observation-only callers. */
    fun commitAll(provisional: List<ProvisionalPredicateBinding>): Boolean = commitObservation(
        BindingPreparation(true, provisional.map(ProvisionalPredicateBinding::asProposal)),
        BindingOrigin.OBSERVATION_ONLY,
    )

    /** Preparation is read-only, so rollback intentionally has no state to undo. */
    fun rollback(@Suppress("UNUSED_PARAMETER") provisional: ProvisionalPredicateBinding?) = Unit

    /** Preparation is read-only, so rollback intentionally has no state to undo. */
    fun rollbackAll(@Suppress("UNUSED_PARAMETER") provisional: List<ProvisionalPredicateBinding>) = Unit

    /** Compatibility helper for callers that intentionally bind immediately. */
    fun bindAction(milestone: TaskMilestone, action: AgentAction, observation: Observation, runId: String? = null): BindingResult {
        val preparation = prepareActionBinding(milestone, action, observation, runId)
        if (!preparation.prepared) return BindingResult(false, reason = preparation.reason)
        if (!commitObservation(preparation, BindingOrigin.OBSERVATION_ONLY)) {
            return BindingResult(false, reason = "predicate binding commit failed")
        }
        val first = preparation.transitions.firstOrNull()?.replacementBinding
        return BindingResult(true, first ?: preparation.inferredExistingBinding, preparation.provisional.firstOrNull())
    }

    /** Read-only binding preparation performed after fresh observation and safety checks. */
    fun prepareActionBinding(
        milestone: TaskMilestone,
        action: AgentAction,
        observation: Observation,
        runId: String? = null,
        resolvedTarget: ResolvedActionTarget? = null,
    ): BindingPreparation {
        if (action is AgentAction.BindPredicate) return prepareExplicitBinding(milestone, action, observation, runId)
        val target = resolvedTarget?.semanticNode ?: actionTarget(action, observation)
            ?: return if (action is AgentAction.ClickText || action is AgentAction.ClickNode ||
                action is AgentAction.InputText || action is AgentAction.SubmitInput || action is AgentAction.EnsureToggle
            ) BindingPreparation(false, reason = "action target is missing or ambiguous") else BindingPreparation(true)
        val candidateKinds = when (action) {
            is AgentAction.InputText, is AgentAction.SubmitInput -> setOf(UiPredicateKind.EDITABLE_EQUALS, UiPredicateKind.ELEMENT_TEXT_EQUALS)
            is AgentAction.EnsureToggle -> setOf(UiPredicateKind.TOGGLE_STATE, UiPredicateKind.TOGGLE_ON, UiPredicateKind.ELEMENT_CHECKED, UiPredicateKind.ELEMENT_SELECTED)
            is AgentAction.ClickNode, is AgentAction.ClickText -> setOf(
                UiPredicateKind.ELEMENT_PRESENT, UiPredicateKind.ELEMENT_DISAPPEARED,
                UiPredicateKind.ELEMENT_ENABLED, UiPredicateKind.ELEMENT_SELECTED,
                UiPredicateKind.ELEMENT_CHECKED, UiPredicateKind.ELEMENT_TEXT_EQUALS,
            )
            else -> emptySet()
        }
        if (candidateKinds.isEmpty()) return BindingPreparation(true)
        val requestedPredicateId = actionPredicateId(action)
        data class Candidate(val index: Int, val existing: PredicateBinding?)
        val candidates = mutableListOf<Candidate>()
        var rejection: String? = null
        milestone.successPredicates.forEachIndexed { index, predicate ->
            if (predicate.kind !in candidateKinds) return@forEachIndexed
            val effectiveId = predicate.predicateId ?: TaskPlanValidator.predicateIdFor(milestone.id, index)
            if (requestedPredicateId != null && requestedPredicateId != effectiveId) return@forEachIndexed
            val hint = predicate.targetHint?.trim().orEmpty()
            if (hint.isNotBlank() && !targetMatchesHint(target, hint)) {
                if (get(milestone.id, index) != null && requestedPredicateId == null) {
                    rejection = "action target conflicts with existing predicate binding"
                }
                return@forEachIndexed
            }
            val selectorMatches = predicate.target?.let { NodeSelector.matchingNodes(observation, it) }
            if (selectorMatches != null && selectorMatches.size != 1) {
                if (requestedPredicateId == effectiveId || get(milestone.id, index) != null) {
                    rejection = if (selectorMatches.isEmpty()) "predicate target missing" else "predicate target is ambiguous"
                }
                return@forEachIndexed
            }
            if (selectorMatches != null && !sameNode(target, selectorMatches.single())) {
                val existing = get(milestone.id, index)
                if (requestedPredicateId == effectiveId) {
                    rejection = "action target conflicts with predicate target"
                } else if (existing != null) {
                    rejection = "action target conflicts with existing predicate binding"
                }
                return@forEachIndexed
            }
            val existing = get(milestone.id, index)
            if (existing != null) {
                if (existing.lifecycle == BindingLifecycle.VERIFIED) {
                    rejection = "predicate is already verified"
                    return@forEachIndexed
                }
                if (existing.lifecycle == BindingLifecycle.INVALIDATED) {
                    rejection = "predicate binding was invalidated"
                    return@forEachIndexed
                }
                val resolution = NodeSelector.resolveIdentity(observation, existing.identity)
                when (resolution) {
                    is IdentityResolution.Found -> {
                        if (!sameNode(target, resolution.node)) {
                            rejection = "action target conflicts with existing predicate binding"
                            return@forEachIndexed
                        }
                    }
                    IdentityResolution.WindowRecreated,
                    IdentityResolution.IdentityInvalidated,
                    -> {
                        if (predicate.kind == UiPredicateKind.ELEMENT_DISAPPEARED) {
                            rejection = "disappeared predicate cannot be rebound after its window identity changed"
                            return@forEachIndexed
                        }
                    }
                    else -> {
                        rejection = "action target conflicts with existing predicate binding"
                        return@forEachIndexed
                    }
                }
            }
            candidates += Candidate(index, existing)
        }
        rejection?.let { return BindingPreparation(false, reason = it) }
        if (candidates.isEmpty()) {
            return if (requestedPredicateId == null) BindingPreparation(true)
            else BindingPreparation(false, reason = "predicateId does not match a compatible current predicate")
        }
        val candidate = if (requestedPredicateId != null) {
            candidates.single()
        } else {
            val existingCandidates = candidates.filter { it.existing != null }
            when {
                existingCandidates.size == 1 -> existingCandidates.single()
                existingCandidates.size > 1 -> return BindingPreparation(false, reason = "multiple compatible existing bindings; predicateId is required")
                candidates.size == 1 -> candidates.single()
                else -> return BindingPreparation(false, reason = "multiple compatible predicates; predicateId is required")
            }
        }
        val existing = get(milestone.id, candidate.index) ?: candidate.existing
        val result = prepareBinding(milestone, candidate.index, target, observation, runId)
        if (!result.bound || result.proposal == null) {
            return BindingPreparation(false, reason = result.reason)
        }
        return BindingPreparation(
            prepared = true,
            transitions = listOf(result.proposal),
            reason = result.reason,
            inferredExistingBinding = existing,
        )
    }

    private fun prepareExplicitBinding(
        milestone: TaskMilestone,
        action: AgentAction.BindPredicate,
        observation: Observation,
        runId: String?,
    ): BindingPreparation {
        val index = milestone.successPredicates.mapIndexed { index, predicate ->
            index to (predicate.predicateId ?: TaskPlanValidator.predicateIdFor(milestone.id, index))
        }.firstOrNull { (_, predicateId) -> predicateId == action.predicateId }?.first ?: -1
        if (index < 0) return BindingPreparation(false, reason = "predicateId is not present in the current milestone")
        val predicate = milestone.successPredicates[index]
        val target = NodeSelector.resolve(observation, action.nodeId, action.selector)
            ?: return BindingPreparation(false, reason = "bind_predicate target is missing or ambiguous")
        get(milestone.id, index)?.let { existing ->
            if (existing.lifecycle == BindingLifecycle.VERIFIED) {
                return BindingPreparation(false, reason = "predicate is already verified")
            }
            if (existing.lifecycle == BindingLifecycle.INVALIDATED) {
                return BindingPreparation(false, reason = "predicate binding was invalidated")
            }
            val resolution = NodeSelector.resolveIdentity(observation, existing.identity)
            when (resolution) {
                is IdentityResolution.Found -> {
                    return if (sameNode(target, resolution.node)) {
                        BindingPreparation(true, inferredExistingBinding = existing, reason = "already_bound")
                    } else {
                        BindingPreparation(false, reason = "predicate binding conflicts with an existing target")
                    }
                }
                IdentityResolution.WindowRecreated,
                IdentityResolution.IdentityInvalidated,
                -> {
                    if (predicate.kind == UiPredicateKind.ELEMENT_DISAPPEARED) {
                        return BindingPreparation(false, reason = "disappeared predicate cannot be rebound after its window identity changed")
                    }
                }
                else -> return BindingPreparation(false, reason = "predicate binding conflicts with an existing target")
            }
        }
        if (predicate.targetHint?.isNotBlank() == true && !targetMatchesHint(target, predicate.targetHint)) {
            return BindingPreparation(false, reason = "bind_predicate target conflicts with predicate targetHint")
        }
        predicate.target?.let { selector ->
            val matches = NodeSelector.matchingNodes(observation, selector)
            if (matches.size != 1) return BindingPreparation(false, reason = if (matches.isEmpty()) "predicate target missing" else "predicate target is ambiguous")
            if (!sameNode(target, matches.single())) {
                return BindingPreparation(false, reason = "bind_predicate target conflicts with predicate selector")
            }
        }
        val result = prepareBinding(milestone, index, target, observation, runId)
        return if (result.bound && result.proposal != null) BindingPreparation(
            true,
            listOf(result.proposal),
            reason = result.reason,
            inferredExistingBinding = result.proposal.existingBinding,
        )
        else BindingPreparation(false, reason = result.reason)
    }

    /** Atomically applies an observation-only or already-satisfied transition. */
    @Synchronized
    fun commitObservation(preparation: BindingPreparation, origin: BindingOrigin): Boolean {
        if (!preparation.prepared || !canCommit(preparation.transitions)) return false
        val updates = preparation.transitions.mapNotNull { proposal ->
            val base = proposal.replacementBinding ?: proposal.existingBinding ?: return@mapNotNull null
            key(base.milestoneId, base.predicateIndex) to base.copy(
                lifecycle = BindingLifecycle.COMMITTED,
                origin = origin,
                resultState = BindingResultState.BOUND_ONLY,
                dispatchedActionKey = null,
                dispatchedObservationId = null,
                dispatchSequence = null,
                strongPostconditionsBefore = emptyMap(),
                strongPostconditionTruthBefore = emptyMap(),
                preDispatchSnapshotId = null,
            )
        }
        updates.forEach { (bindingKey, binding) -> bindings[bindingKey] = binding }
        return true
    }

    /** Atomically commits a successful side effect and updates inferred existing bindings too. */
    @Synchronized
    fun commitMutation(
        preparation: BindingPreparation,
        actionKey: String,
        observationId: String,
        strongPostconditionsBefore: Map<String, Boolean>,
        preDispatchSnapshotId: String? = null,
        strongPostconditionTruthBefore: Map<String, PredicateTruth> = emptyMap(),
    ): Boolean {
        if (!preparation.prepared || !canCommit(preparation.transitions)) return false
        val sequence = ++dispatchSequence
        val updates = preparation.transitions.mapNotNull { proposal ->
            val base = proposal.replacementBinding ?: proposal.existingBinding ?: return@mapNotNull null
            key(base.milestoneId, base.predicateIndex) to base.copy(
                lifecycle = BindingLifecycle.COMMITTED,
                origin = BindingOrigin.MUTATING_ACTION,
                dispatchedActionKey = actionKey,
                dispatchedObservationId = observationId,
                dispatchSequence = sequence,
                resultState = BindingResultState.DISPATCHED,
                strongPostconditionsBefore = strongPostconditionsBefore,
                strongPostconditionTruthBefore = strongPostconditionTruthBefore,
                preDispatchSnapshotId = preDispatchSnapshotId,
            )
        }
        updates.forEach { (bindingKey, binding) -> bindings[bindingKey] = binding }
        return true
    }

    @Synchronized
    fun markResultUnknown(preparation: BindingPreparation, actionKey: String) =
        updateDispatchState(preparation, actionKey, BindingResultState.RESULT_UNKNOWN)

    @Synchronized
    fun markResultObserved(preparation: BindingPreparation, actionKey: String) =
        updateDispatchState(preparation, actionKey, BindingResultState.RESULT_OBSERVED)

    fun retainCompleted(
        previous: TaskPlan,
        revised: TaskPlan,
        @Suppress("UNUSED_PARAMETER") completedIds: Set<String>,
    ) {
        val retained = linkedMapOf<String, PredicateBinding>()
        bindings.values.forEach { binding ->
            val previousMilestone = previous.milestones.firstOrNull { it.id == binding.milestoneId } ?: return@forEach
            val previousPredicate = previousMilestone.successPredicates.getOrNull(binding.predicateIndex) ?: return@forEach
            val previousId = previousPredicate.predicateId ?: TaskPlanValidator.predicateIdFor(previousMilestone.id, binding.predicateIndex)
            val revisedMatches = revised.milestones.flatMap { revisedMilestone ->
                revisedMilestone.successPredicates.mapIndexedNotNull { index, predicate ->
                    val revisedId = predicate.predicateId ?: TaskPlanValidator.predicateIdFor(revisedMilestone.id, index)
                    if (revisedId == previousId && predicatesRemainBound(previousPredicate, predicate, previousId, revisedId)) {
                        revisedMilestone to index
                    } else {
                        null
                    }
                }
            }
            if (revisedMatches.size != 1) return@forEach
            val (revisedMilestone, revisedMatch) = revisedMatches.single()
            val remapped = binding.copy(
                milestoneId = revisedMilestone.id,
                predicateIndex = revisedMatch,
                predicateId = previousId,
            )
            retained[key(revisedMilestone.id, revisedMatch)] = remapped
        }
        bindings.clear()
        bindings.putAll(retained)
    }

    fun rollbackRun(runId: String) {
        bindings.entries.removeIf { it.value.runId == runId }
    }

    /** Compatibility two-phase transition used by older local tests. */
    fun markDispatched(provisional: List<ProvisionalPredicateBinding>): Boolean {
        val proposals = provisional.map(ProvisionalPredicateBinding::asProposal)
        if (!canCommit(proposals)) return false
        val sequence = ++dispatchSequence
        proposals.forEach { proposal ->
            val base = proposal.replacementBinding ?: proposal.existingBinding ?: return@forEach
            bindings[key(base.milestoneId, base.predicateIndex)] = base.copy(
                lifecycle = BindingLifecycle.DISPATCHED,
                origin = BindingOrigin.MUTATING_ACTION,
                resultState = BindingResultState.DISPATCHED,
                dispatchSequence = sequence,
            )
        }
        return true
    }

    /** Commits only bindings that were marked as dispatched. */
    fun commitDispatched(provisional: List<ProvisionalPredicateBinding>): Boolean {
        val keys = provisional.map { key(it.binding.milestoneId, it.binding.predicateIndex) }
        if (keys.any { bindings[it]?.lifecycle != BindingLifecycle.DISPATCHED }) return false
        keys.forEach { bindingKey ->
            bindings[bindingKey] = bindings.getValue(bindingKey).copy(lifecycle = BindingLifecycle.COMMITTED)
        }
        return true
    }

    fun markVerified(milestoneId: String) {
        bindings.entries
            .filter { it.value.milestoneId == milestoneId && it.value.lifecycle == BindingLifecycle.COMMITTED }
            .forEach { (bindingKey, binding) ->
                bindings[bindingKey] = binding.copy(
                    lifecycle = BindingLifecycle.VERIFIED,
                    resultState = BindingResultState.VERIFIED,
                )
            }
    }

    fun invalidateRun(runId: String) {
        bindings.entries
            .filter { it.value.runId == runId }
            .forEach { (bindingKey, binding) -> bindings[bindingKey] = binding.copy(lifecycle = BindingLifecycle.INVALIDATED) }
    }

    private fun predicatesRemainBound(previous: UiPredicate, revised: UiPredicate, previousId: String, revisedId: String): Boolean {
        return previous.kind == revised.kind &&
            previousId == revisedId &&
            previous.valueRef == revised.valueRef &&
            previous.literal == revised.literal &&
            previous.expectedChecked == revised.expectedChecked &&
            previous.targetPackage == revised.targetPackage &&
            previous.target == revised.target &&
            TargetHintMatcher.semanticallyEquivalent(previous.targetHint, revised.targetHint)
    }

    private fun actionTarget(action: AgentAction, observation: Observation): UiNodeSnapshot? = TargetResolver.resolve(action, observation)

    private fun actionPredicateId(action: AgentAction): String? = when (action) {
        is AgentAction.ClickText -> action.predicateId
        is AgentAction.ClickNode -> action.predicateId
        is AgentAction.InputText -> action.predicateId
        is AgentAction.SubmitInput -> action.predicateId
        is AgentAction.EnsureToggle -> action.predicateId
        else -> null
    }

    private fun targetMatchesHint(node: UiNodeSnapshot, hint: String): Boolean {
        return TargetHintMatcher.match(hint, node) == TargetHintResult.MATCH
    }

    private fun sameNode(first: UiNodeSnapshot, second: UiNodeSnapshot): Boolean {
        if (first.packageName.isNotBlank() && second.packageName.isNotBlank() && first.packageName != second.packageName) return false
        if (first.windowId != null && second.windowId != null && first.windowId != second.windowId) return false
        if (first.id == second.id) return true
        if (first.withinWindowStableKey.isNotBlank() && second.withinWindowStableKey.isNotBlank()) {
            return first.withinWindowStableKey == second.withinWindowStableKey && first.className == second.className
        }
        if (first.treePath != null && second.treePath != null) {
            return first.treePath == second.treePath && first.className == second.className
        }
        if (first.bounds == second.bounds && first.className == second.className &&
            first.viewId.isNotBlank() && second.viewId.isNotBlank()
        ) {
            return first.viewId == second.viewId
        }
        if (first.bounds == second.bounds && first.className == second.className) {
            return first.text == second.text || first.description == second.description ||
                (first.text.isBlank() && second.text.isBlank() && first.description.isBlank() && second.description.isBlank())
        }
        return false
    }

    private fun proposalResult(
        existing: PredicateBinding?,
        replacement: PredicateBinding,
        requestedLifecycle: BindingLifecycle,
        reason: String,
    ): BindingResult {
        val proposal = BindingTransitionProposal(existing, requestedLifecycle, replacement, reason)
        val provisional = ProvisionalPredicateBinding(replacement, existing, requestedLifecycle, reason)
        return BindingResult(true, replacement, provisional, proposal, reason)
    }

    private fun canCommit(proposals: List<BindingTransitionProposal>): Boolean = proposals.all { proposal ->
        val binding = proposal.replacementBinding ?: proposal.existingBinding ?: return@all false
        val current = bindings[key(binding.milestoneId, binding.predicateIndex)]
        current == proposal.existingBinding &&
            current?.lifecycle !in setOf(BindingLifecycle.VERIFIED, BindingLifecycle.INVALIDATED) &&
            (proposal.existingBinding == null || proposal.existingBinding.predicateId == binding.predicateId)
    }

    private fun updateDispatchState(
        preparation: BindingPreparation,
        actionKey: String,
        state: BindingResultState,
    ): Boolean {
        val keys = preparation.transitions.mapNotNull { proposal ->
            (proposal.replacementBinding ?: proposal.existingBinding)?.let { key(it.milestoneId, it.predicateIndex) }
        }
        if (keys.any { bindingKey -> bindings[bindingKey]?.dispatchedActionKey != actionKey }) return false
        keys.forEach { bindingKey ->
            bindings[bindingKey]?.let { binding -> bindings[bindingKey] = binding.copy(resultState = state) }
        }
        return true
    }

    private fun key(milestoneId: String, predicateIndex: Int): String = "$milestoneId#$predicateIndex"
}

object MilestoneEvaluator {
    const val BOUND_WINDOW_GONE_REPLAN_REASON = "bound window disappeared but no new strong postcondition was observed"

    fun evaluate(
        milestone: TaskMilestone,
        plan: TaskPlan,
        observation: Observation,
        targetPackage: String?,
        bindings: PredicateBindingStore? = null,
        predicateIndices: Set<Int>? = null,
        runId: String? = null,
        computePositiveEvidence: Boolean = true,
        preDispatchSnapshots: PreDispatchEvidenceStore? = null,
    ): PredicateEvidence {
        val details = mutableListOf<String>()
        val truths = linkedMapOf<String, PredicateTruth>()
        var replanRequired = false
        val indices = predicateIndices ?: milestone.successPredicates.indices.toSet()
        val positiveIndices = indices.filter { index ->
            milestone.successPredicates.getOrNull(index)?.kind in STRONG_POSTCONDITION_KINDS
        }.toSet()
        val positiveEvidence: Map<Int, PredicateTruth> = if (!computePositiveEvidence || positiveIndices.isEmpty()) {
            emptyMap()
        } else {
            positiveIndices.associateWith { positiveIndex ->
                val positiveId = predicateId(milestone, positiveIndex)
                evaluate(
                    milestone = milestone,
                    plan = plan,
                    observation = observation,
                    targetPackage = targetPackage,
                    bindings = bindings,
                    predicateIndices = setOf(positiveIndex),
                    runId = runId,
                    computePositiveEvidence = false,
                    preDispatchSnapshots = preDispatchSnapshots,
                ).truthFor(positiveId)
            }
        }
        val results = milestone.successPredicates.mapIndexedNotNull { predicateIndex, predicate ->
            if (predicateIndex !in indices) return@mapIndexedNotNull null
            if (predicate.kind == UiPredicateKind.SEMANTIC_CLAIM) {
                details += "${predicate.kind}=AUXILIARY: ${predicate.description}"
                return@mapIndexedNotNull null
            }
            val currentPredicateId = predicateId(milestone, predicateIndex)
            val value = when (predicate.valueRef) {
                "goal_text" -> plan.goal.originalGoal
                else -> predicate.literal
            }
            val rawBound = bindings?.get(milestone.id, predicateIndex)
            val bound = rawBound?.takeIf { runId == null || it.runId == null || it.runId == runId }
            val targetSelector = bound?.boundSelector ?: predicate.target
            val identityResolution = bound?.let { binding ->
                NodeSelector.resolveIdentity(observation, binding.identity)
            }
            val targetMatches = bound?.let { binding ->
                when (val resolution = identityResolution) {
                    is IdentityResolution.Found -> listOf(resolution.node)
                    is IdentityResolution.Ambiguous -> NodeSelector.matchingNodes(observation, binding.identity)
                    else -> emptyList()
                }
            } ?: targetSelector?.let { selector -> NodeSelector.matchingNodes(observation, selector) }
                ?: predicate.targetHint?.let { hint ->
                    observation.nodes.filter { node -> TargetHintMatcher.match(hint, node) == TargetHintResult.MATCH }
                }
                ?: emptyList()
            val target = targetMatches.singleOrNull()
            val concreteTarget = bound != null || targetMatches.size == 1
            // State predicates must carry a concrete selector or an existing
            // runtime binding.  A fuzzy targetHint alone is never a proof,
            // even when it happens to match one node in this snapshot.
            val concreteStateTarget = bound != null || (predicate.target != null && targetMatches.size == 1)
            val targetHintWithoutUniqueTarget = bound == null && predicate.target == null &&
                !predicate.targetHint.isNullOrBlank() && targetMatches.size != 1
            val bindingRequired = predicate.kind in setOf(
                UiPredicateKind.EDITABLE_EQUALS,
                UiPredicateKind.ELEMENT_PRESENT,
                UiPredicateKind.ELEMENT_DISAPPEARED,
                UiPredicateKind.ELEMENT_ENABLED,
                UiPredicateKind.ELEMENT_SELECTED,
                UiPredicateKind.ELEMENT_CHECKED,
                UiPredicateKind.ELEMENT_TEXT_EQUALS,
                UiPredicateKind.TOGGLE_STATE,
                UiPredicateKind.TOGGLE_ON,
            )
            fun observableTruth(proven: Boolean, forceUnknown: Boolean = false): PredicateTruth = when {
                forceUnknown -> PredicateTruth.UNKNOWN
                proven -> PredicateTruth.PROVEN
                observation.privacyFiltered || !observation.isComplete -> PredicateTruth.UNKNOWN
                else -> PredicateTruth.REFUTED
            }
            val truth = when (predicate.kind) {
                UiPredicateKind.PACKAGE_FOREGROUND -> observableTruth(
                    observation.packageName == (
                        predicate.targetPackage
                            ?: predicate.target?.packageName
                            ?: targetPackage
                        ),
                )

                UiPredicateKind.TEXT_PRESENT -> if (value == null) {
                    PredicateTruth.UNKNOWN
                } else if (predicate.target != null || bound != null || !predicate.targetHint.isNullOrBlank()) {
                    if (targetHintWithoutUniqueTarget) {
                        PredicateTruth.UNKNOWN
                    } else {
                        target?.let { node ->
                            if (node.password || node.isInputMethod) {
                                PredicateTruth.UNKNOWN
                            } else {
                                observableTruth(
                                    node.visible && (node.text.equals(value, true) || node.description.equals(value, true)),
                                )
                            }
                        } ?: observableTruth(false)
                    }
                } else {
                    if (observation.nodes.any { node ->
                        node.visible && !node.password && !node.isInputMethod &&
                            (node.text.equals(value, true) || node.description.equals(value, true))
                    } || observation.ocrText.lineSequence().any { it.trim().equals(value, true) }) {
                        PredicateTruth.PROVEN
                    } else if (observation.privacyFiltered || !observation.isComplete) {
                        PredicateTruth.UNKNOWN
                    } else {
                        PredicateTruth.REFUTED
                    }
                }

                UiPredicateKind.EDITABLE_EQUALS -> if (!bindingRequired || !concreteStateTarget || value == null || target == null) {
                    PredicateTruth.UNKNOWN
                } else if (target.password) {
                    PredicateTruth.UNKNOWN
                } else {
                    observableTruth(target.visible && target.enabled && target.editable && target.text == value)
                }

                UiPredicateKind.IME_HIDDEN -> observableTruth(!observation.imeVisible)
                UiPredicateKind.ELEMENT_PRESENT -> if (!bindingRequired || !concreteStateTarget || target == null) {
                    PredicateTruth.UNKNOWN
                } else if (target.password || target.isInputMethod) {
                    PredicateTruth.UNKNOWN
                } else {
                    observableTruth(target.visible)
                }
                UiPredicateKind.ELEMENT_DISAPPEARED -> if (!bindingRequired || bound == null) {
                    PredicateTruth.UNKNOWN
                } else when (identityResolution) {
                    IdentityResolution.MissingInSameWindow -> if (observation.isComplete && !observation.privacyFiltered) {
                        PredicateTruth.PROVEN
                    } else {
                        PredicateTruth.UNKNOWN
                    }
                    IdentityResolution.BoundWindowGone -> if (boundWindowGoneCanProve(
                        binding = bound,
                        observation = observation,
                        strongPostconditionsAfter = positiveEvidence.mapKeys { (index, _) ->
                            predicateId(milestone, index)
                        },
                        milestone = milestone,
                        plan = plan,
                        targetPackage = targetPackage,
                        runId = runId,
                        bindings = bindings,
                        preDispatchSnapshots = preDispatchSnapshots,
                    )) PredicateTruth.PROVEN else PredicateTruth.UNKNOWN
                    else -> PredicateTruth.UNKNOWN
                }
                UiPredicateKind.ELEMENT_ENABLED -> if (!bindingRequired || !concreteStateTarget || target == null) PredicateTruth.UNKNOWN else observableTruth(target.visible && target.enabled)
                UiPredicateKind.ELEMENT_SELECTED -> if (!bindingRequired || !concreteStateTarget || target == null) PredicateTruth.UNKNOWN else observableTruth(target.visible && target.selected)
                UiPredicateKind.ELEMENT_CHECKED -> if (!bindingRequired || !concreteStateTarget || target == null) PredicateTruth.UNKNOWN else observableTruth(target.visible && target.checked == true)
                UiPredicateKind.ELEMENT_TEXT_EQUALS -> if (!bindingRequired || !concreteStateTarget || value == null || target == null) {
                    PredicateTruth.UNKNOWN
                } else if (target.password) {
                    PredicateTruth.UNKNOWN
                } else {
                    observableTruth(target.visible && (target.text == value || target.description == value))
                }
                UiPredicateKind.TOGGLE_STATE -> if (!bindingRequired || !concreteStateTarget || target == null) {
                    PredicateTruth.UNKNOWN
                } else {
                    observableTruth(target.visible && target.checked != null && target.checked == predicate.expectedChecked)
                }
                UiPredicateKind.TOGGLE_ON -> if (!bindingRequired || !concreteStateTarget || target == null) {
                    PredicateTruth.UNKNOWN
                } else {
                    observableTruth(target.visible && (target.checked == true || target.selected))
                }
                UiPredicateKind.ELEMENT_STATE,
                UiPredicateKind.SEMANTIC_CLAIM,
                -> PredicateTruth.UNKNOWN
            }
            val detail = if (truth != PredicateTruth.PROVEN && predicate.kind == UiPredicateKind.ELEMENT_DISAPPEARED && identityResolution == IdentityResolution.BoundWindowGone) {
                replanRequired = true
                BOUND_WINDOW_GONE_REPLAN_REASON
            } else {
                predicate.description
            }
            truths[currentPredicateId] = truth
            details += "${predicate.kind}=${truth.name}: $detail"
            truth
        }
        return PredicateEvidence(
            proven = results.isNotEmpty() && results.all { it == PredicateTruth.PROVEN },
            details = details,
            replanRequired = replanRequired,
            truths = truths,
        )
    }

    fun evaluatePredicateTruth(
        milestone: TaskMilestone,
        plan: TaskPlan,
        observation: Observation,
        targetPackage: String?,
        predicateIndex: Int,
        bindings: PredicateBindingStore? = null,
        runId: String? = null,
        preDispatchSnapshots: PreDispatchEvidenceStore? = null,
    ): PredicateTruth {
        val predicateId = milestone.successPredicates.getOrNull(predicateIndex)?.let { predicate ->
            predicate.predicateId ?: TaskPlanValidator.predicateIdFor(milestone.id, predicateIndex)
        } ?: return PredicateTruth.UNKNOWN
        return evaluate(
            milestone = milestone,
            plan = plan,
            observation = observation,
            targetPackage = targetPackage,
            bindings = bindings,
            predicateIndices = setOf(predicateIndex),
            runId = runId,
            computePositiveEvidence = false,
            preDispatchSnapshots = preDispatchSnapshots,
        ).truthFor(predicateId)
    }

    /**
     * Evaluates a predicate against an immutable pre-dispatch snapshot. Unlike
     * live milestone evaluation, complete absence of a concrete selector or
     * bound identity is deterministic REFUTED evidence.
     */
    fun evaluateHistoricalPredicateTruth(
        predicate: UiPredicate,
        predicateId: String,
        preDispatchSnapshot: PreDispatchEvidenceSnapshot,
        postActionBinding: PredicateBinding?,
        targetPackage: String?,
        resolvedValue: String? = predicate.literal,
    ): PredicateTruth {
        if (!preDispatchSnapshot.isComplete || preDispatchSnapshot.privacyFiltered) return PredicateTruth.UNKNOWN
        if (!predicate.targetHint.isNullOrBlank() && predicate.target == null &&
            (postActionBinding == null || postActionBinding.predicateId != predicateId)
        ) return PredicateTruth.UNKNOWN

        val observation = preDispatchSnapshot.toObservation()
        val binding = postActionBinding?.takeIf { it.predicateId == predicateId }
        val historicalIdentity = binding?.let { resolveHistoricalIdentity(preDispatchSnapshot, it.identity) }
        if (historicalIdentity == HistoricalIdentityResolution.Ambiguous ||
            historicalIdentity == HistoricalIdentityResolution.IdentityInvalidated
        ) return PredicateTruth.UNKNOWN
        val candidates = when {
            historicalIdentity is HistoricalIdentityResolution.Found -> listOf(historicalIdentity.node)
            historicalIdentity == HistoricalIdentityResolution.Missing -> emptyList()
            predicate.target != null -> NodeSelector.matchingNodes(observation, predicate.target)
            else -> emptyList()
        }
        val targetScoped = binding != null || predicate.target != null
        if (targetScoped && candidates.size > 1) return PredicateTruth.UNKNOWN
        val target = candidates.singleOrNull()

        fun targetTruth(test: (UiNodeSnapshot) -> Boolean): PredicateTruth {
            if (!targetScoped) return PredicateTruth.UNKNOWN
            if (target == null) return PredicateTruth.REFUTED
            if (target.password || target.isInputMethod) return PredicateTruth.UNKNOWN
            return if (test(target)) PredicateTruth.PROVEN else PredicateTruth.REFUTED
        }

        return when (predicate.kind) {
            UiPredicateKind.PACKAGE_FOREGROUND -> if (
                preDispatchSnapshot.packageName == (
                    predicate.targetPackage ?: predicate.target?.packageName ?: targetPackage
                    )
            ) PredicateTruth.PROVEN else PredicateTruth.REFUTED

            UiPredicateKind.IME_HIDDEN -> if (!preDispatchSnapshot.imeVisible) PredicateTruth.PROVEN else PredicateTruth.REFUTED
            UiPredicateKind.TEXT_PRESENT -> {
                val value = resolvedValue ?: return PredicateTruth.UNKNOWN
                if (targetScoped) {
                    targetTruth { node ->
                        node.visible && (node.text.equals(value, true) || node.description.equals(value, true))
                    }
                } else if (observation.nodes.any { node ->
                        node.visible && !node.password && !node.isInputMethod &&
                            (node.text.equals(value, true) || node.description.equals(value, true))
                    }
                ) PredicateTruth.PROVEN else PredicateTruth.REFUTED
            }

            UiPredicateKind.EDITABLE_EQUALS -> {
                val value = resolvedValue ?: return PredicateTruth.UNKNOWN
                targetTruth { node -> node.visible && node.enabled && node.editable && node.text == value }
            }
            UiPredicateKind.ELEMENT_PRESENT -> targetTruth { it.visible }
            UiPredicateKind.ELEMENT_ENABLED -> targetTruth { it.visible && it.enabled }
            UiPredicateKind.ELEMENT_SELECTED -> targetTruth { it.visible && it.selected }
            UiPredicateKind.ELEMENT_CHECKED -> targetTruth { it.visible && it.checked == true }
            UiPredicateKind.ELEMENT_TEXT_EQUALS -> {
                val value = resolvedValue ?: return PredicateTruth.UNKNOWN
                targetTruth { node -> node.visible && (node.text == value || node.description == value) }
            }
            UiPredicateKind.TOGGLE_STATE -> targetTruth { node ->
                node.visible && node.checked != null && node.checked == predicate.expectedChecked
            }
            UiPredicateKind.TOGGLE_ON -> targetTruth { node -> node.visible && (node.checked == true || node.selected) }
            UiPredicateKind.ELEMENT_DISAPPEARED,
            UiPredicateKind.ELEMENT_STATE,
            UiPredicateKind.SEMANTIC_CLAIM,
            -> PredicateTruth.UNKNOWN
        }
    }

    private fun boundWindowGoneCanProve(
        binding: PredicateBinding,
        observation: Observation,
        strongPostconditionsAfter: Map<String, PredicateTruth>,
        milestone: TaskMilestone,
        plan: TaskPlan,
        targetPackage: String?,
        runId: String?,
        bindings: PredicateBindingStore?,
        preDispatchSnapshots: PreDispatchEvidenceStore?,
    ): Boolean {
        if (binding.origin != BindingOrigin.MUTATING_ACTION) return false
        if (binding.lifecycle !in setOf(BindingLifecycle.COMMITTED, BindingLifecycle.VERIFIED)) return false
        if (binding.resultState !in setOf(
                BindingResultState.DISPATCHED,
                BindingResultState.RESULT_UNKNOWN,
                BindingResultState.RESULT_OBSERVED,
                BindingResultState.VERIFIED,
            )) return false
        if (binding.boundPackage.isBlank() || observation.packageName != binding.boundPackage) return false
        if (binding.dispatchedObservationId.isNullOrBlank() || binding.dispatchedObservationId == observation.observationId) return false
        val identity = binding.identity
        val originalWindowId = identity.windowId ?: return false
        val originalWindowStillExists = observation.windowPackages[originalWindowId]?.let { it == binding.boundPackage }
            ?: (originalWindowId in observation.windowIds)
        if (originalWindowStillExists) return false
        val samePackageNodes = observation.nodes.filter { node ->
            identity.packageName.isBlank() || node.packageName == identity.packageName
        }
        val originalStructureKey = identity.crossWindowStructureKey ?: return false
        if (samePackageNodes.any { node -> crossWindowKey(node) == originalStructureKey }) return false
        val snapshot = preDispatchSnapshots?.get(binding.preDispatchSnapshotId)
        val predicateIndicesById = milestone.successPredicates.mapIndexed { index, _ ->
            predicateId(milestone, index) to index
        }.toMap()
        return strongPostconditionsAfter.any { (predicateKey, afterTruth) ->
            if (afterTruth != PredicateTruth.PROVEN) return@any false
            val index = predicateIndicesById[predicateKey]
            val before = if (snapshot != null && index != null) {
                val predicate = milestone.successPredicates[index]
                evaluateHistoricalPredicateTruth(
                    predicate = predicate,
                    predicateId = predicateKey,
                    preDispatchSnapshot = snapshot,
                    postActionBinding = bindings?.get(milestone.id, index),
                    targetPackage = targetPackage,
                    resolvedValue = when (predicate.valueRef) {
                        "goal_text" -> plan.goal.originalGoal
                        else -> predicate.literal
                    },
                )
            } else {
                binding.strongPostconditionTruthBefore[predicateKey]
                    ?: binding.strongPostconditionsBefore[predicateKey]?.let { proven ->
                        if (proven) PredicateTruth.PROVEN else PredicateTruth.REFUTED
                    }
                    ?: PredicateTruth.UNKNOWN
            }
            before == PredicateTruth.REFUTED
        }
    }

    private fun resolveHistoricalIdentity(
        snapshot: PreDispatchEvidenceSnapshot,
        identity: BoundElementIdentity,
    ): HistoricalIdentityResolution {
        if (identity.packageName.isBlank()) return HistoricalIdentityResolution.IdentityInvalidated
        val packageObservable = snapshot.packageName == identity.packageName ||
            snapshot.windowPackages.values.any { it == identity.packageName }
        if (!packageObservable) return HistoricalIdentityResolution.IdentityInvalidated

        val packageNodes = snapshot.nodes.filter { it.packageName == identity.packageName }
        if (packageNodes.isEmpty()) return HistoricalIdentityResolution.Missing

        identity.viewIdResourceName?.let { viewId ->
            val matches = packageNodes.filter { it.viewIdResourceName == viewId }
            return when {
                matches.size > 1 -> HistoricalIdentityResolution.Ambiguous
                matches.size == 1 -> validateHistoricalCandidate(matches.single(), identity)
                else -> historicalMissingOrInvalidated(packageNodes, identity)
            }
        }

        val structureKey = identity.crossWindowStructureKey
            ?: return HistoricalIdentityResolution.IdentityInvalidated
        val matches = packageNodes.filter { node ->
            historicalCrossWindowKey(node) == structureKey &&
                (identity.className.isBlank() || node.className == identity.className)
        }
        return when {
            matches.size > 1 -> HistoricalIdentityResolution.Ambiguous
            matches.size == 1 -> validateHistoricalCandidate(matches.single(), identity)
            else -> historicalMissingOrInvalidated(packageNodes, identity)
        }
    }

    private fun historicalMissingOrInvalidated(
        packageNodes: List<PreDispatchNodeSnapshot>,
        identity: BoundElementIdentity,
    ): HistoricalIdentityResolution {
        val emptyHash = TraceSanitizer.digest("")
        val possibleRelocations = packageNodes.filter { node ->
            (identity.className.isBlank() || node.className == identity.className) &&
                ((identity.treePath != null && node.treePath == identity.treePath) ||
                    (identity.initialTextHash != emptyHash && node.textHash == identity.initialTextHash) ||
                    (identity.initialDescriptionHash != emptyHash && node.descriptionHash == identity.initialDescriptionHash))
        }
        return when {
            possibleRelocations.size > 1 -> HistoricalIdentityResolution.Ambiguous
            possibleRelocations.size == 1 -> HistoricalIdentityResolution.IdentityInvalidated
            else -> HistoricalIdentityResolution.Missing
        }
    }

    private fun validateHistoricalCandidate(
        node: PreDispatchNodeSnapshot,
        identity: BoundElementIdentity,
    ): HistoricalIdentityResolution {
        val classMatches = identity.className.isBlank() || node.className == identity.className
        val windowMatches = identity.windowId == null || node.windowId == identity.windowId
        val treePathMatches = identity.treePath == null || node.treePath == identity.treePath
        val structureMatches = identity.crossWindowStructureKey == null ||
            historicalCrossWindowKey(node) == identity.crossWindowStructureKey
        val withinWindowMatches = identity.withinWindowStableKey == null ||
            node.withinWindowStableKey == identity.withinWindowStableKey
        if (!classMatches || !windowMatches || !treePathMatches || !structureMatches || !withinWindowMatches) {
            return HistoricalIdentityResolution.IdentityInvalidated
        }
        return HistoricalIdentityResolution.Found(node.toUiNodeSnapshot())
    }

    private fun PreDispatchNodeSnapshot.toUiNodeSnapshot(): UiNodeSnapshot = UiNodeSnapshot(
        id = id,
        text = safeText.orEmpty(),
        description = safeDescription.orEmpty(),
        className = className,
        clickable = false,
        editable = editable,
        bounds = bounds.orEmpty(),
        withinWindowStableKey = withinWindowStableKey.orEmpty(),
        crossWindowStructureKey = crossWindowStructureKey.orEmpty(),
        viewId = viewIdResourceName.orEmpty(),
        treePath = treePath,
        enabled = enabled,
        focused = focused,
        checked = checked,
        selected = selected,
        packageName = packageName,
        visible = visible,
        password = password,
        windowId = windowId,
    )

    private fun historicalCrossWindowKey(node: PreDispatchNodeSnapshot): String =
        node.crossWindowStructureKey ?: NodeIdentityKeys.crossWindowStructureKey(
            node.packageName,
            node.viewIdResourceName.orEmpty(),
            node.className,
            node.treePath.orEmpty(),
        )

    fun strongPostconditionBaseline(
        milestone: TaskMilestone,
        plan: TaskPlan,
        observation: Observation,
        targetPackage: String?,
        bindings: PredicateBindingStore?,
        runId: String? = null,
        preDispatchSnapshots: PreDispatchEvidenceStore? = null,
    ): Map<String, Boolean> = strongPostconditionTruthBaseline(
        milestone = milestone,
        plan = plan,
        observation = observation,
        targetPackage = targetPackage,
        bindings = bindings,
        runId = runId,
        preDispatchSnapshots = preDispatchSnapshots,
    ).mapValues { (_, truth) -> truth == PredicateTruth.PROVEN }

    fun strongPostconditionTruthBaseline(
        milestone: TaskMilestone,
        plan: TaskPlan,
        observation: Observation,
        targetPackage: String?,
        bindings: PredicateBindingStore?,
        runId: String? = null,
        preDispatchSnapshots: PreDispatchEvidenceStore? = null,
    ): Map<String, PredicateTruth> = milestone.successPredicates.mapIndexedNotNull { index, predicate ->
        if (predicate.kind !in STRONG_POSTCONDITION_KINDS) return@mapIndexedNotNull null
        val truth = evaluate(
            milestone = milestone,
            plan = plan,
            observation = observation,
            targetPackage = targetPackage,
            bindings = bindings,
            predicateIndices = setOf(index),
            runId = runId,
            computePositiveEvidence = false,
            preDispatchSnapshots = preDispatchSnapshots,
        ).truthFor(predicateId(milestone, index))
        predicateId(milestone, index) to truth
    }.toMap()

    private fun predicateId(milestone: TaskMilestone, index: Int): String =
        milestone.successPredicates[index].predicateId ?: TaskPlanValidator.predicateIdFor(milestone.id, index)

    private fun crossWindowKey(node: UiNodeSnapshot): String = node.crossWindowStructureKey.ifBlank {
        NodeIdentityKeys.crossWindowStructureKey(
            node.packageName,
            node.viewId,
            node.className,
            node.treePath.orEmpty(),
        )
    }

    private val STRONG_POSTCONDITION_KINDS = setOf(
        UiPredicateKind.TEXT_PRESENT,
        UiPredicateKind.EDITABLE_EQUALS,
        UiPredicateKind.ELEMENT_PRESENT,
        UiPredicateKind.ELEMENT_ENABLED,
        UiPredicateKind.ELEMENT_SELECTED,
        UiPredicateKind.ELEMENT_CHECKED,
        UiPredicateKind.ELEMENT_TEXT_EQUALS,
        UiPredicateKind.TOGGLE_STATE,
        UiPredicateKind.TOGGLE_ON,
    )

    fun evaluateHardPredicates(
        milestone: TaskMilestone,
        plan: TaskPlan,
        observation: Observation,
        targetPackage: String?,
        bindings: PredicateBindingStore? = null,
        runId: String? = null,
    ): PredicateEvidence {
        val hardPredicates = milestone.successPredicates.filter { predicate ->
            predicate.kind in setOf(
                UiPredicateKind.PACKAGE_FOREGROUND,
                UiPredicateKind.TEXT_PRESENT,
                UiPredicateKind.EDITABLE_EQUALS,
                UiPredicateKind.IME_HIDDEN,
                UiPredicateKind.ELEMENT_PRESENT,
                UiPredicateKind.ELEMENT_DISAPPEARED,
                UiPredicateKind.ELEMENT_ENABLED,
                UiPredicateKind.ELEMENT_SELECTED,
                UiPredicateKind.ELEMENT_CHECKED,
                UiPredicateKind.ELEMENT_TEXT_EQUALS,
                UiPredicateKind.TOGGLE_STATE,
                UiPredicateKind.TOGGLE_ON,
            )
        }
        if (hardPredicates.isEmpty()) return PredicateEvidence(true, listOf("No deterministic predicates"))
        return evaluate(
            milestone,
            plan,
            observation,
            targetPackage,
            bindings,
            milestone.successPredicates.indices.filter { milestone.successPredicates[it] in hardPredicates }.toSet(),
            runId,
        )
    }
}

data class PackagePolicy(
    val allowedPackages: MutableSet<String> = mutableSetOf(),
    val primaryPackage: String? = null,
    val allowSystemUi: Boolean = false,
    val allowTemporaryExternalPackages: Boolean = false,
    val temporaryPackages: Set<String> = emptySet(),
) {
    fun allows(packageName: String, isSystemUi: Boolean = isSystemUiPackage(packageName)): Boolean {
        if (packageName.isBlank()) return false
        val normalized = packageName.lowercase()
        // Protected system surfaces are checked before the model-provided
        // allowlist.  An LLM cannot grant itself installer/permission access.
        if (isPermissionOrInstallerPackage(normalized)) return false
        if (isSystemUiPackage(normalized) || isSystemUi || normalized == "android") return allowSystemUi
        if (normalized in allowedPackages.map(String::lowercase).toSet()) return true
        return allowTemporaryExternalPackages && normalized in temporaryPackages.map(String::lowercase).toSet()
    }

    companion object {
        private val permissionAndInstallerPrefixes = setOf(
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
            "com.android.settings",
            "com.google.android.settings",
            "com.samsung.android.packageinstaller",
            "com.samsung.android.settings",
        )

        fun isSystemUiPackage(packageName: String): Boolean {
            val normalized = packageName.lowercase()
            return normalized == "com.android.systemui" || normalized.startsWith("com.android.systemui.")
        }

        fun isPermissionOrInstallerPackage(packageName: String): Boolean {
            val normalized = packageName.trim().lowercase()
            return permissionAndInstallerPrefixes.any(normalized::startsWith) ||
                normalized.contains(".permissioncontroller") ||
                normalized.contains(".packageinstaller")
        }

        fun isProtectedPackage(packageName: String): Boolean {
            val normalized = packageName.trim().lowercase()
            return normalized == "android" || isSystemUiPackage(normalized) || isPermissionOrInstallerPackage(normalized)
        }

        fun filterPlannerPackages(requested: Set<String>, installedPackages: Set<String>): Set<String> =
            requested.filterTo(linkedSetOf()) { packageName ->
                packageName in installedPackages &&
                    !isProtectedPackage(packageName)
            }

        fun mergeAllowedPackages(current: Set<String>, requested: Set<String>, installedPackages: Set<String>): Set<String> =
            (current + filterPlannerPackages(requested, installedPackages)).toSet()
    }
}

data class GuardResult(
    val action: AgentAction?,
    val rejection: String? = null,
    val shortCircuit: ActionExecutionResult? = null,
    val resolvedTarget: ResolvedActionTarget? = null,
)

/** Generic pre-tool validation. It never rewrites model-generated input. */
class ToolGuard(
    private val plan: TaskPlan,
    private val packagePolicy: PackagePolicy = PackagePolicy(primaryPackage = null),
) {
    constructor(plan: TaskPlan, targetPackage: String?) : this(
        plan,
        PackagePolicy(
            allowedPackages = targetPackage?.takeIf(String::isNotBlank)?.let { mutableSetOf(it) } ?: mutableSetOf(),
            primaryPackage = targetPackage?.takeIf(String::isNotBlank),
        ),
    )

    fun normalizeAndValidate(
        action: AgentAction,
        observation: Observation,
        milestone: TaskMilestone? = null,
    ): GuardResult {
        if (milestone?.kind == TaskMilestoneKind.LAUNCH_APP && observation.packageName != launchPackage(milestone)) {
            launchPackage(milestone)?.let { return GuardResult(AgentAction.LaunchApp(it)) }
        }
        var resolvedTarget: ResolvedActionTarget? = null
        fun resolveRequiredTarget(label: String): GuardResult? {
            val resolution = TargetResolver.resolveActionTarget(action, observation)
            val target = resolution.target
            if (target != null) {
                resolvedTarget = target
                return null
            }
            val reason = when (resolution.failure) {
                ActionTargetFailure.AMBIGUOUS -> "$label target is ambiguous in the current observation"
                ActionTargetFailure.NOT_ACTIONABLE -> "$label target is not actionable in the current observation"
                else -> "$label target is missing in the current observation"
            }
            return GuardResult(null, reason)
        }
        when (action) {
            is AgentAction.ClickNode -> {
                resolveRequiredTarget("click_node")?.let { return it }
                val target = resolvedTarget!!.semanticNode
                val effective = resolvedTarget!!.effectiveActionNode
                if (target.isInputMethod || effective.isInputMethod ||
                    isInputMethodPackage(target.packageName) || isInputMethodPackage(effective.packageName)
                ) {
                    return GuardResult(null, "direct IME key clicks are forbidden; use submit_input")
                }
            }

            is AgentAction.ClickText -> {
                if (action.text.isBlank()) return GuardResult(null, "click_text requires non-blank visible text")
                resolveRequiredTarget("click_text")?.let { return it }
            }

            is AgentAction.InputText -> {
                if (action.text.length > MAX_INPUT_LENGTH) return GuardResult(null, "input_text is too long")
                resolveRequiredTarget("input_text")?.let { return it }
                if (resolvedTarget!!.semanticNode.password) return GuardResult(null, "input_text cannot target a password field")
            }

            is AgentAction.SubmitInput -> {
                resolveRequiredTarget("submit_input")?.let { return it }
                if (resolvedTarget!!.semanticNode.password) return GuardResult(null, "submit_input cannot target a password field")
            }

            is AgentAction.EnsureToggle -> {
                resolveRequiredTarget("ensure_toggle")?.let { return it }
                if (resolvedTarget!!.dispatchMode == ActionDispatchMode.OBSERVATION_ONLY) {
                    return GuardResult(
                        action = null,
                        shortCircuit = ActionExecutionResult(true, "already_satisfied", "toggle already has the requested state"),
                        resolvedTarget = resolvedTarget,
                    )
                }
            }

            is AgentAction.BindPredicate -> {
                if (action.predicateId.isBlank()) return GuardResult(null, "bind_predicate requires predicateId")
                resolveRequiredTarget("bind_predicate")?.let { return it }
            }

            is AgentAction.TapPoint -> if (observation.imeVisible) {
                return GuardResult(null, "visual point taps are forbidden while the IME is visible")
            }

            is AgentAction.LaunchApp -> if (action.packageName == observation.packageName) {
                return GuardResult(null, "target app is already foreground")
            }

            else -> Unit
        }
        return GuardResult(action, resolvedTarget = resolvedTarget)
    }

    fun requiredWorkflowAction(observation: Observation, milestone: TaskMilestone? = null): AgentAction? =
        if (milestone?.kind == TaskMilestoneKind.LAUNCH_APP) {
            launchPackage(milestone)?.takeIf { observation.packageName != it }?.let(AgentAction::LaunchApp)
        } else null

    fun recordDispatch(@Suppress("UNUSED_PARAMETER") action: AgentAction) = Unit

    private fun isInputMethodPackage(packageName: String?): Boolean {
        val value = packageName.orEmpty().lowercase()
        return value.contains("inputmethod") || value.contains("keyboard") || value.endsWith(".ime") || value.contains(".ime.")
    }

    private fun launchPackage(milestone: TaskMilestone): String? = milestone.successPredicates
        .firstOrNull { it.kind == UiPredicateKind.PACKAGE_FOREGROUND }
        ?.let { it.targetPackage ?: it.target?.packageName }
        ?: packagePolicy.primaryPackage

    private companion object { const val MAX_INPUT_LENGTH = 2_000 }
}

data class StepTrace(
    val milestoneId: String,
    val beforeId: String,
    val action: String,
    val afterId: String,
    val judgement: TransitionJudgement,
    val evidence: String,
)

class RunLedger(private var plan: TaskPlan) {
    var currentMilestoneIndex: Int = 0
        private set
    private val fingerprints = ArrayDeque<String>()
    private val attempts = mutableMapOf<String, Int>()
    private val unknownDispatches = mutableSetOf<String>()
    private val traces = mutableListOf<StepTrace>()
    private val evidence = linkedMapOf<String, String>()
    var noProgressCount: Int = 0
        private set

    val currentMilestone: TaskMilestone? get() = plan.milestones.getOrNull(currentMilestoneIndex)
    val complete: Boolean get() = currentMilestoneIndex >= plan.milestones.size

    fun replacePlan(newPlan: TaskPlan, completedMilestones: Int) {
        plan = newPlan
        currentMilestoneIndex = completedMilestones.coerceIn(0, newPlan.milestones.size)
        noProgressCount = 0
        fingerprints.clear()
        val retainedIds = newPlan.milestones.take(currentMilestoneIndex).mapTo(mutableSetOf()) { it.id }
        evidence.keys.retainAll(retainedIds)
    }

    fun observe(observation: Observation) {
        val fingerprint = observation.stateFingerprint()
        if (fingerprints.lastOrNull() == fingerprint) return
        fingerprints.addLast(fingerprint)
        while (fingerprints.size > 16) fingerprints.removeFirst()
    }

    /** Read-only duplicate check. Attempts are charged only by recordDispatch after execution. */
    fun blockRepeated(
        action: AgentAction,
        observation: Observation,
        resolvedTarget: ResolvedActionTarget? = null,
        sideEffectIdentity: SideEffectIdentity? = null,
    ): String? {
        val milestone = currentMilestone ?: return null
        val unknownKey = unknownActionKey(milestone, action, observation, resolvedTarget, sideEffectIdentity)
        if (unknownKey in unknownDispatches) {
            return "previous dispatch result is unknown for the same milestone, action, and target"
        }
        val key = screenActionKey(milestone, action, observation, resolvedTarget, sideEffectIdentity)
        return if (attempts.getOrDefault(key, 0) >= MAX_ATTEMPTS_PER_SCREEN) {
            "strategy exhausted for the same milestone and screen"
        } else null
    }

    fun repeatedRecoveryReason(
        action: AgentAction,
        observation: Observation,
        resolvedTarget: ResolvedActionTarget? = null,
        sideEffectIdentity: SideEffectIdentity? = null,
    ): RecoveryReason? {
        val milestone = currentMilestone ?: return null
        return if (unknownActionKey(milestone, action, observation, resolvedTarget, sideEffectIdentity) in unknownDispatches) {
            RecoveryReason.RESULT_UNKNOWN
        } else if (blockRepeated(action, observation, resolvedTarget, sideEffectIdentity) != null) {
            RecoveryReason.REPEATED_ACTION
        } else {
            null
        }
    }

    fun recordDispatch(
        action: AgentAction,
        observation: Observation,
        resultState: DispatchResultState = DispatchResultState.CONFIRMED,
        resolvedTarget: ResolvedActionTarget? = null,
        sideEffectIdentity: SideEffectIdentity? = null,
    ) {
        if (action is AgentAction.Wait || action is AgentAction.BindPredicate) return
        val milestone = currentMilestone ?: return
        if (resultState == DispatchResultState.FAILED) return
        val key = screenActionKey(milestone, action, observation, resolvedTarget, sideEffectIdentity)
        attempts[key] = attempts.getOrDefault(key, 0) + 1
        if (resultState == DispatchResultState.RESULT_UNKNOWN) {
            unknownDispatches += unknownActionKey(milestone, action, observation, resolvedTarget, sideEffectIdentity)
        }
    }

    fun actionKey(
        action: AgentAction,
        observation: Observation,
        resolvedTarget: ResolvedActionTarget? = null,
        sideEffectIdentity: SideEffectIdentity? = null,
    ): String = stableActionKey(action, observation, resolvedTarget, sideEffectIdentity)

    fun record(trace: StepTrace) {
        traces += trace
        if (trace.judgement == TransitionJudgement.NO_PROGRESS) noProgressCount += 1 else noProgressCount = 0
    }

    fun advance(proof: String): String {
        val completed = currentMilestone?.id ?: "none"
        evidence[completed] = proof
        currentMilestoneIndex += 1
        noProgressCount = 0
        return "$completed proven: $proof"
    }

    fun cyclePeriod(): Int? {
        val trail = fingerprints.toList()
        for (period in 1..4) {
            if (trail.size >= period * 2 && trail.takeLast(period) == trail.dropLast(period).takeLast(period)) return period
        }
        return null
    }

    fun recentFailureContext(): String = traces.takeLast(10).joinToString("\n") {
        "${it.milestoneId}: ${it.action} -> ${it.judgement} (${it.evidence})"
    }

    fun planText(bindings: PredicateBindingStore? = null): String = plan.compactText(currentMilestoneIndex, bindings)

    fun evidenceSummary(): String = if (evidence.isEmpty()) "No milestone evidence recorded" else evidence.entries.joinToString("\n") { (id, proof) -> "$id: $proof" }

    private fun screenActionKey(
        milestone: TaskMilestone,
        action: AgentAction,
        observation: Observation,
        resolvedTarget: ResolvedActionTarget?,
        sideEffectIdentity: SideEffectIdentity?,
    ): String = "${milestone.id}|${observation.stateFingerprint()}|${stableActionKey(action, observation, resolvedTarget, sideEffectIdentity)}"

    private fun unknownActionKey(
        @Suppress("UNUSED_PARAMETER") milestone: TaskMilestone,
        action: AgentAction,
        observation: Observation,
        resolvedTarget: ResolvedActionTarget?,
        sideEffectIdentity: SideEffectIdentity?,
    ): String = stableActionKey(action, observation, resolvedTarget, sideEffectIdentity)

    private fun stableActionKey(
        action: AgentAction,
        observation: Observation,
        resolvedTarget: ResolvedActionTarget?,
        sideEffectIdentity: SideEffectIdentity?,
    ): String {
        sideEffectIdentity?.let { identity ->
            return listOf(
                "family=${identity.family.name}",
                "package=${identity.targetPackage ?: "no-package"}",
                "target=${identity.targetCrossWindowStructureKey ?: "no-target"}",
                "payload=${identity.irreversiblePayloadDigest.orEmpty()}",
                "desired=${identity.desiredState.orEmpty()}",
                "generation=${identity.inputGeneration?.toString().orEmpty()}",
            ).joinToString("|")
        }
        val target = resolvedTarget?.effectiveActionNode ?: when (action) {
            is AgentAction.BindPredicate -> TargetResolver.resolve(action, observation)
            else -> TargetResolver.resolveActionTarget(action, observation).target?.effectiveActionNode
        }
        val targetPackage = resolvedTarget?.targetPackage?.takeIf(String::isNotBlank)
            ?: target?.packageName?.takeIf(String::isNotBlank)
            ?: (action as? AgentAction.LaunchApp)?.packageName
            ?: observation.packageName.takeIf(String::isNotBlank)
            ?: "no-package"
        val targetKey = target?.let { node ->
            TargetResolver.crossWindowStructureKey(node)
        } ?: when (action) {
            is AgentAction.TapPoint -> "${action.x},${action.y}"
            else -> "no-target"
        }
        val payload = when (action) {
            is AgentAction.InputText -> "${action.text.length}:${action.text.hashCode()}:${action.mode}:${action.submit}"
            is AgentAction.ClickText -> ""
            is AgentAction.ClickNode -> ""
            is AgentAction.SubmitInput -> ""
            is AgentAction.EnsureToggle -> action.desired.toString()
            is AgentAction.BindPredicate -> action.predicateId
            is AgentAction.LaunchApp -> action.packageName
            is AgentAction.Swipe -> action.direction
            is AgentAction.Wait -> action.milliseconds.toString()
            is AgentAction.Finish -> action.reason.hashCode().toString()
            is AgentAction.Fail -> action.reason.hashCode().toString()
            is AgentAction.TapPoint, AgentAction.Back, AgentAction.Home -> ""
        }
        val desiredState = (action as? AgentAction.EnsureToggle)?.desired?.toString().orEmpty()
        val actionFamily = when (action) {
            is AgentAction.ClickText, is AgentAction.ClickNode -> SideEffectFamily.ACTIVATE_CONTROL.name
            is AgentAction.EnsureToggle -> SideEffectFamily.SET_BOOLEAN_CONTROL.name
            is AgentAction.InputText -> SideEffectFamily.INPUT_VALUE.name
            is AgentAction.SubmitInput -> SideEffectFamily.SUBMIT_VALUE.name
            else -> TraceSanitizer.actionType(action)
        }
        return listOf(
            "family=$actionFamily",
            "package=$targetPackage",
            "target=$targetKey",
            "payload=$payload",
            "desired=$desiredState",
            "generation=",
        ).joinToString("|")
    }

    private companion object { const val MAX_ATTEMPTS_PER_SCREEN = 4 }
}
