package com.androidagent.app.agent

data class PredicateEvidence(val proven: Boolean, val details: List<String>)

data class PredicateBinding(
    val milestoneId: String,
    val predicateIndex: Int,
    val predicateId: String?,
    val boundSelector: ElementSelector,
    val identity: BoundElementIdentity,
    val boundObservationId: String,
    val boundPackage: String,
    val runId: String? = null,
)

data class ProvisionalPredicateBinding(val binding: PredicateBinding)

data class BindingResult(
    val bound: Boolean,
    val binding: PredicateBinding? = null,
    val provisional: ProvisionalPredicateBinding? = null,
    val reason: String = "",
)

data class BindingPreparation(
    val prepared: Boolean,
    val provisional: List<ProvisionalPredicateBinding> = emptyList(),
    val reason: String = "",
) {
    val bound: Boolean get() = prepared
}

/**
 * Runtime-owned binding ledger. Manager plans may contain only target hints;
 * this ledger records the unique node selected from the live observation
 * immediately before the action that may change the screen.
 */
class PredicateBindingStore {
    private val bindings = linkedMapOf<String, PredicateBinding>()

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
        result.provisional?.let(::commit)
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
        val identity = BoundElementIdentity.from(node)
        val matches = NodeSelector.matchingNodes(observation, identity)
        if (matches.size != 1) return BindingResult(false, reason = if (matches.isEmpty()) "target missing" else "target is ambiguous")
        val binding = PredicateBinding(
            milestoneId = milestone.id,
            predicateIndex = predicateIndex,
            predicateId = predicate.predicateId ?: TaskPlanValidator.predicateIdFor(milestone.id, predicateIndex),
            boundSelector = NodeSelector.from(node),
            identity = identity,
            boundObservationId = observation.observationId,
            boundPackage = node.packageName.ifBlank { observation.packageName },
            runId = runId,
        )
        return BindingResult(true, binding, ProvisionalPredicateBinding(binding))
    }

    fun commit(provisional: ProvisionalPredicateBinding): Boolean {
        val binding = provisional.binding
        val existing = bindings[key(binding.milestoneId, binding.predicateIndex)]
        if (existing != null && existing != binding) return false
        bindings[key(binding.milestoneId, binding.predicateIndex)] = binding
        return true
    }

    fun commitAll(provisional: List<ProvisionalPredicateBinding>): Boolean {
        if (provisional.any { existingConflict(it) }) return false
        provisional.forEach(::commit)
        return true
    }

    fun rollback(provisional: ProvisionalPredicateBinding?) {
        provisional ?: return
        val binding = provisional.binding
        if (bindings[key(binding.milestoneId, binding.predicateIndex)] == binding) {
            bindings.remove(key(binding.milestoneId, binding.predicateIndex))
        }
    }

    fun rollbackAll(provisional: List<ProvisionalPredicateBinding>) = provisional.forEach(::rollback)

    /** Compatibility helper for callers that intentionally bind immediately. */
    fun bindAction(milestone: TaskMilestone, action: AgentAction, observation: Observation, runId: String? = null): BindingResult {
        val preparation = prepareActionBinding(milestone, action, observation, runId)
        if (!preparation.prepared) return BindingResult(false, reason = preparation.reason)
        commitAll(preparation.provisional)
        val first = preparation.provisional.firstOrNull()?.binding
        return BindingResult(true, first, preparation.provisional.firstOrNull())
    }

    /** Read-only binding preparation performed after fresh observation and safety checks. */
    fun prepareActionBinding(
        milestone: TaskMilestone,
        action: AgentAction,
        observation: Observation,
        runId: String? = null,
    ): BindingPreparation {
        if (action is AgentAction.BindPredicate) return prepareExplicitBinding(milestone, action, observation, runId)
        val target = actionTarget(action, observation)
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
        val candidates = milestone.successPredicates.mapIndexedNotNull { index, predicate ->
            if (predicate.kind !in candidateKinds || get(milestone.id, index) != null) return@mapIndexedNotNull null
            val effectiveId = predicate.predicateId ?: TaskPlanValidator.predicateIdFor(milestone.id, index)
            if (requestedPredicateId != null && requestedPredicateId != effectiveId) return@mapIndexedNotNull null
            val hint = predicate.targetHint?.trim().orEmpty()
            if (hint.isNotBlank() && !targetMatchesHint(target, hint)) return@mapIndexedNotNull null
            val selectorMatches = predicate.target?.let { NodeSelector.matchingNodes(observation, it) }
            if (selectorMatches != null && selectorMatches.size != 1) {
                if (requestedPredicateId != null) {
                    return BindingPreparation(false, reason = if (selectorMatches.isEmpty()) "predicate target missing" else "predicate target is ambiguous")
                }
                // A selector that does not describe this action is simply not
                // a compatible predicate.  An ambiguous selector remains a
                // hard safety failure because we cannot know which predicate
                // the action would satisfy.
                if (selectorMatches.isEmpty()) return@mapIndexedNotNull null
                return BindingPreparation(false, reason = "predicate target is ambiguous")
            }
            if (selectorMatches != null && !sameNode(target, selectorMatches.single())) {
                if (requestedPredicateId != null) return BindingPreparation(false, reason = "action target conflicts with predicate target")
                return@mapIndexedNotNull null
            }
            index
        }
        if (candidates.isEmpty()) {
            return if (requestedPredicateId == null) BindingPreparation(true)
            else BindingPreparation(false, reason = "predicateId does not match a compatible current predicate")
        }
        if (requestedPredicateId == null && candidates.size > 1) {
            return BindingPreparation(false, reason = "multiple compatible predicates; predicateId is required")
        }
        val prepared = candidates.map { index ->
            val result = prepareBinding(milestone, index, target, observation, runId)
            if (!result.bound || result.provisional == null) {
                return BindingPreparation(false, reason = result.reason)
            }
            result.provisional
        }
        return BindingPreparation(true, prepared)
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
        if (predicate.targetHint?.isNotBlank() == true && !targetMatchesHint(target, predicate.targetHint)) {
            return BindingPreparation(false, reason = "bind_predicate target conflicts with predicate targetHint")
        }
        predicate.target?.let { selector ->
            val matches = NodeSelector.matchingNodes(observation, selector)
            if (matches.size != 1) return BindingPreparation(false, reason = if (matches.isEmpty()) "predicate target missing" else "predicate target is ambiguous")
            if (!sameNode(target, matches.single())) return BindingPreparation(false, reason = "bind_predicate target conflicts with predicate selector")
        }
        val result = prepareBinding(milestone, index, target, observation, runId)
        return if (result.bound && result.provisional != null) BindingPreparation(true, listOf(result.provisional))
        else BindingPreparation(false, reason = result.reason)
    }

    fun retainCompleted(previous: TaskPlan, revised: TaskPlan, completedIds: Set<String>) {
        val revisedKeys = revised.milestones.flatMap { milestone ->
            milestone.successPredicates.indices.map { index -> key(milestone.id, index) }
        }.toSet()
        val retained = bindings.filter { (bindingKey, binding) ->
            if (bindingKey !in revisedKeys) {
                false
            } else if (binding.milestoneId in completedIds) {
                true
            } else {
                val previousPredicate = previous.milestones
                    .firstOrNull { it.id == binding.milestoneId }
                    ?.successPredicates
                    ?.getOrNull(binding.predicateIndex)
                val revisedPredicate = revised.milestones
                    .firstOrNull { it.id == binding.milestoneId }
                    ?.successPredicates
                    ?.getOrNull(binding.predicateIndex)
                previousPredicate != null && revisedPredicate != null && predicatesRemainBound(previousPredicate, revisedPredicate)
            }
        }
        bindings.clear()
        bindings.putAll(retained)
    }

    fun rollbackRun(runId: String) {
        bindings.entries.removeIf { it.value.runId == runId }
    }

    private fun predicatesRemainBound(previous: UiPredicate, revised: UiPredicate): Boolean {
        if (previous.predicateId != null || revised.predicateId != null) return previous.predicateId == revised.predicateId
        return previous.kind == revised.kind &&
            previous.valueRef == revised.valueRef &&
            previous.literal == revised.literal &&
            previous.target == revised.target &&
            previous.targetPackage == revised.targetPackage &&
            previous.targetHint == revised.targetHint &&
            previous.expectedChecked == revised.expectedChecked
    }

    private fun actionTarget(action: AgentAction, observation: Observation): UiNodeSnapshot? = when (action) {
        is AgentAction.ClickText -> {
            val matches = observation.nodes.filter { it.visible && it.enabled && !it.editable &&
                (it.text.equals(action.text, true) || it.description.equals(action.text, true)) }
            matches.singleOrNull()
        }
        is AgentAction.ClickNode -> NodeSelector.resolve(observation, action.nodeId, action.selector)
        is AgentAction.InputText -> if (action.nodeId != null || action.target != null) {
            NodeSelector.resolve(observation, action.nodeId, action.target)
        } else {
            observation.nodes.filter { it.visible && it.enabled && it.editable && !it.password }.singleOrNull { it.focused }
        }
        is AgentAction.SubmitInput -> if (action.nodeId != null || action.target != null) {
            NodeSelector.resolve(observation, action.nodeId, action.target)
        } else {
            observation.nodes.filter { it.visible && it.enabled && it.editable && !it.password }.singleOrNull { it.focused }
        }
        is AgentAction.EnsureToggle -> NodeSelector.resolve(observation, action.nodeId, action.selector)
        else -> null
    }

    private fun actionPredicateId(action: AgentAction): String? = when (action) {
        is AgentAction.ClickText -> action.predicateId
        is AgentAction.ClickNode -> action.predicateId
        is AgentAction.InputText -> action.predicateId
        is AgentAction.SubmitInput -> action.predicateId
        is AgentAction.EnsureToggle -> action.predicateId
        else -> null
    }

    private fun targetMatchesHint(node: UiNodeSnapshot, hint: String): Boolean {
        val lower = hint.lowercase()
        return node.text.lowercase().contains(lower) || node.description.lowercase().contains(lower) ||
            node.className.lowercase().contains(lower) || node.viewId.lowercase().contains(lower)
    }

    private fun sameNode(first: UiNodeSnapshot, second: UiNodeSnapshot): Boolean {
        if (first.packageName.isNotBlank() && second.packageName.isNotBlank() && first.packageName != second.packageName) return false
        if (first.windowId != null && second.windowId != null && first.windowId != second.windowId) return false
        if (first.id == second.id) return true
        if (first.stableKey.isNotBlank() && second.stableKey.isNotBlank()) {
            return first.stableKey == second.stableKey && first.className == second.className
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

    private fun existingConflict(provisional: ProvisionalPredicateBinding): Boolean {
        val binding = provisional.binding
        val existing = bindings[key(binding.milestoneId, binding.predicateIndex)]
        return existing != null && existing != binding
    }

    private fun key(milestoneId: String, predicateIndex: Int): String = "$milestoneId#$predicateIndex"
}

object MilestoneEvaluator {
    fun evaluate(
        milestone: TaskMilestone,
        plan: TaskPlan,
        observation: Observation,
        targetPackage: String?,
        bindings: PredicateBindingStore? = null,
        predicateIndices: Set<Int>? = null,
        runId: String? = null,
    ): PredicateEvidence {
        val details = mutableListOf<String>()
        val results = milestone.successPredicates.mapIndexedNotNull { predicateIndex, predicate ->
            if (predicateIndices != null && predicateIndex !in predicateIndices) return@mapIndexedNotNull null
            if (predicate.kind == UiPredicateKind.SEMANTIC_CLAIM) {
                details += "${predicate.kind}=AUXILIARY: ${predicate.description}"
                return@mapIndexedNotNull null
            }
            val value = when (predicate.valueRef) {
                "goal_text" -> plan.goal.originalGoal
                else -> predicate.literal
            }
            val rawBound = bindings?.get(milestone.id, predicateIndex)
            val bound = rawBound?.takeIf { runId == null || it.runId == null || it.runId == runId }
            val targetSelector = bound?.boundSelector ?: predicate.target
            val targetMatches = bound?.let { binding ->
                NodeSelector.matchingNodes(observation, binding.identity)
            } ?: targetSelector?.let { selector -> NodeSelector.matchingNodes(observation, selector) }.orEmpty()
            val target = targetMatches.singleOrNull()
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
            val proven = when (predicate.kind) {
                UiPredicateKind.PACKAGE_FOREGROUND ->
                    observation.packageName == (
                        predicate.targetPackage
                            ?: predicate.target?.packageName
                            ?: targetPackage
                        )

                UiPredicateKind.TEXT_PRESENT -> value != null && if (predicate.target != null || bound != null) {
                    target?.let { node ->
                        node.visible && !node.password && !node.isInputMethod &&
                            (node.text.equals(value, true) || node.description.equals(value, true))
                    } == true
                } else {
                    observation.nodes.any { node ->
                        node.visible && !node.password && !node.isInputMethod &&
                            (node.text.equals(value, true) || node.description.equals(value, true))
                    }
                }

                UiPredicateKind.EDITABLE_EQUALS -> bindingRequired && bound != null && value != null && target?.let { node ->
                    node.visible && node.enabled && node.editable && !node.password && node.text == value
                } == true

                UiPredicateKind.IME_HIDDEN -> !observation.imeVisible
                UiPredicateKind.ELEMENT_PRESENT -> bindingRequired && bound != null && targetMatches.size == 1 && targetMatches.single().visible
                UiPredicateKind.ELEMENT_DISAPPEARED -> bindingRequired && bound != null && targetMatches.isEmpty()
                UiPredicateKind.ELEMENT_ENABLED -> bindingRequired && bound != null && target?.let { it.visible && it.enabled } == true
                UiPredicateKind.ELEMENT_SELECTED -> bindingRequired && bound != null && target?.let { it.visible && it.selected } == true
                UiPredicateKind.ELEMENT_CHECKED -> bindingRequired && bound != null && target?.let { it.visible && it.checked == true } == true
                UiPredicateKind.ELEMENT_TEXT_EQUALS -> bindingRequired && bound != null && value != null && target?.let { node ->
                    node.visible && (node.text == value || node.description == value)
                } == true
                UiPredicateKind.TOGGLE_STATE -> bindingRequired && bound != null && target?.let { node ->
                    node.visible && node.checked != null && node.checked == predicate.expectedChecked
                } == true
                UiPredicateKind.TOGGLE_ON -> bindingRequired && bound != null && target?.let { node ->
                    node.visible && (node.checked == true || node.selected)
                } == true
                UiPredicateKind.ELEMENT_STATE -> false
                UiPredicateKind.SEMANTIC_CLAIM -> false
            }
            details += "${predicate.kind}=${if (proven) "PROVEN" else "UNKNOWN"}: ${predicate.description}"
            proven
        }
        return PredicateEvidence(results.isNotEmpty() && results.all { it }, details)
    }

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
        )

        fun isSystemUiPackage(packageName: String): Boolean {
            val normalized = packageName.lowercase()
            return normalized == "com.android.systemui" || normalized.startsWith("com.android.systemui.")
        }

        fun isPermissionOrInstallerPackage(packageName: String): Boolean =
            permissionAndInstallerPrefixes.any(packageName::startsWith)

        fun filterPlannerPackages(requested: Set<String>, installedPackages: Set<String>): Set<String> =
            requested.filterTo(linkedSetOf()) { packageName ->
                packageName in installedPackages &&
                    !isSystemUiPackage(packageName) &&
                    !isPermissionOrInstallerPackage(packageName)
            }

        fun mergeAllowedPackages(current: Set<String>, requested: Set<String>, installedPackages: Set<String>): Set<String> =
            (current + filterPlannerPackages(requested, installedPackages)).toSet()
    }
}

data class GuardResult(
    val action: AgentAction?,
    val rejection: String? = null,
    val shortCircuit: ActionExecutionResult? = null,
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
        when (action) {
            is AgentAction.ClickNode -> {
                val target = resolveTarget(observation, action.nodeId, action.selector)
                    ?: return GuardResult(null, "node selector is missing or ambiguous in the current observation")
                if (!target.visible || !target.enabled) return GuardResult(null, "node is not visible and enabled")
                if (target.isInputMethod || isInputMethodPackage(target.packageName)) {
                    return GuardResult(null, "direct IME key clicks are forbidden; use submit_input")
                }
            }

            is AgentAction.ClickText -> {
                if (action.text.isBlank()) return GuardResult(null, "click_text requires non-blank visible text")
                val matches = observation.nodes.count { node ->
                    node.visible && node.enabled && !node.editable &&
                        (node.text.equals(action.text, true) || node.description.equals(action.text, true))
                }
                if (matches > 1) return GuardResult(null, "click_text target is ambiguous in the current observation")
            }

            is AgentAction.InputText -> {
                if (action.text.length > MAX_INPUT_LENGTH) return GuardResult(null, "input_text is too long")
                val target = resolveEditable(observation, action.nodeId, action.target)
                if ((action.nodeId != null || action.target != null) && target == null) {
                    return GuardResult(null, "input_text selector did not resolve to a unique editable target")
                }
                if (target == null) {
                    val editable = editableNodes(observation)
                    if (editable.count { it.focused } == 1) return GuardResult(action)
                    if (editable.size > 1) return GuardResult(null, "input_text target is ambiguous; provide nodeId or selector")
                    if (editable.isEmpty()) return GuardResult(null, "input_text has no editable target")
                }
            }

            is AgentAction.SubmitInput -> {
                val target = resolveEditable(observation, action.nodeId, action.target)
                if ((action.nodeId != null || action.target != null) && target == null) {
                    return GuardResult(null, "submit_input selector did not resolve to a unique editable target")
                }
                val editable = editableNodes(observation)
                if (target == null && editable.count { it.focused } == 0 && editable.size != 1) {
                    return GuardResult(null, "submit_input target is ambiguous or missing")
                }
            }

            is AgentAction.EnsureToggle -> {
                val target = resolveTarget(observation, action.nodeId, action.selector)
                    ?: return GuardResult(null, "toggle selector is missing or ambiguous")
                if (!target.visible || !target.enabled || target.password) return GuardResult(null, "toggle target is not actionable")
                val current = target.checked ?: target.selected
                if (current == action.desired) {
                    return GuardResult(
                        action = null,
                        shortCircuit = ActionExecutionResult(true, "already_satisfied", "toggle already has the requested state"),
                    )
                }
            }

            is AgentAction.BindPredicate -> {
                if (action.predicateId.isBlank()) return GuardResult(null, "bind_predicate requires predicateId")
                if (NodeSelector.resolve(observation, action.nodeId, action.selector) == null) {
                    return GuardResult(null, "bind_predicate target is missing or ambiguous")
                }
            }

            is AgentAction.TapPoint -> if (observation.imeVisible) {
                return GuardResult(null, "visual point taps are forbidden while the IME is visible")
            }

            is AgentAction.LaunchApp -> if (action.packageName == observation.packageName) {
                return GuardResult(null, "target app is already foreground")
            }

            else -> Unit
        }
        return GuardResult(action)
    }

    fun requiredWorkflowAction(observation: Observation, milestone: TaskMilestone? = null): AgentAction? =
        if (milestone?.kind == TaskMilestoneKind.LAUNCH_APP) {
            launchPackage(milestone)?.takeIf { observation.packageName != it }?.let(AgentAction::LaunchApp)
        } else null

    fun recordDispatch(@Suppress("UNUSED_PARAMETER") action: AgentAction) = Unit

    private fun resolveEditable(observation: Observation, nodeId: Int?, selector: ElementSelector?): UiNodeSnapshot? =
        NodeSelector.resolve(observation, nodeId, selector)?.takeIf { it.editable && it.visible && it.enabled && !it.password }

    private fun resolveTarget(observation: Observation, nodeId: Int?, selector: ElementSelector?): UiNodeSnapshot? =
        NodeSelector.resolve(observation, nodeId, selector)

    private fun editableNodes(observation: Observation): List<UiNodeSnapshot> = observation.nodes.filter {
        it.visible && it.enabled && it.editable && !it.password && !it.isInputMethod
    }

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
    fun blockRepeated(action: AgentAction, observation: Observation): String? {
        val milestone = currentMilestone ?: return null
        val key = actionKey(milestone, action, observation)
        return if (attempts.getOrDefault(key, 0) >= MAX_ATTEMPTS_PER_SCREEN) {
            "strategy exhausted for the same milestone and screen"
        } else null
    }

    fun recordDispatch(action: AgentAction, observation: Observation) {
        if (action is AgentAction.Wait || action is AgentAction.BindPredicate) return
        val milestone = currentMilestone ?: return
        val key = actionKey(milestone, action, observation)
        attempts[key] = attempts.getOrDefault(key, 0) + 1
    }

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

    fun planText(): String = plan.compactText(currentMilestoneIndex)

    fun evidenceSummary(): String = if (evidence.isEmpty()) "No milestone evidence recorded" else evidence.entries.joinToString("\n") { (id, proof) -> "$id: $proof" }

    private fun actionKey(milestone: TaskMilestone, action: AgentAction, observation: Observation): String =
        "${milestone.id}|${observation.stateFingerprint()}|${action::class.simpleName}:${action.toString()}"

    private companion object { const val MAX_ATTEMPTS_PER_SCREEN = 2 }
}
