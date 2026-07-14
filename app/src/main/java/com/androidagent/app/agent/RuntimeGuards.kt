package com.androidagent.app.agent

data class PredicateEvidence(val proven: Boolean, val details: List<String>)

object MilestoneEvaluator {
    fun evaluate(
        milestone: TaskMilestone,
        plan: TaskPlan,
        observation: Observation,
        targetPackage: String?,
    ): PredicateEvidence {
        val details = mutableListOf<String>()
        val results = milestone.successPredicates.map { predicate ->
            val value = when (predicate.valueRef) {
                "goal_text" -> plan.goal.originalGoal
                else -> predicate.literal
            }
            val target = predicate.target?.let { selector ->
                NodeSelector.matchingNodes(observation, selector).singleOrNull()
            }
            val proven = when (predicate.kind) {
                UiPredicateKind.PACKAGE_FOREGROUND ->
                    observation.packageName == (
                        predicate.targetPackage
                            ?: predicate.target?.packageName
                            ?: targetPackage
                        )

                UiPredicateKind.TEXT_PRESENT -> value != null && if (predicate.target != null) {
                    target?.let { node ->
                        node.visible && node.enabled && !node.password && !node.isInputMethod &&
                            (node.text.equals(value, true) || node.description.equals(value, true))
                    } == true
                } else {
                    observation.nodes.any { node ->
                        node.visible && node.enabled && !node.password && !node.isInputMethod &&
                            (node.text.equals(value, true) || node.description.equals(value, true))
                    }
                }

                UiPredicateKind.EDITABLE_EQUALS -> value != null && target?.let { node ->
                    node.visible && node.enabled && node.editable && !node.password && node.text == value
                } == true

                UiPredicateKind.IME_HIDDEN -> !observation.imeVisible
                UiPredicateKind.TOGGLE_ON -> target?.let { node ->
                    node.visible && node.enabled && (node.checked == true || node.selected)
                } == true
                UiPredicateKind.ELEMENT_STATE -> value != null && target?.let { node ->
                    node.visible && node.enabled && (
                        node.text.equals(value, true) || node.description.equals(value, true) || node.viewId == value
                        )
                } == true
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
    ): PredicateEvidence {
        val hardPredicates = milestone.successPredicates.filter { predicate ->
            predicate.kind in setOf(
                UiPredicateKind.PACKAGE_FOREGROUND,
                UiPredicateKind.TEXT_PRESENT,
                UiPredicateKind.EDITABLE_EQUALS,
                UiPredicateKind.IME_HIDDEN,
                UiPredicateKind.ELEMENT_STATE,
                UiPredicateKind.TOGGLE_ON,
            )
        }
        if (hardPredicates.isEmpty()) return PredicateEvidence(true, listOf("No deterministic predicates"))
        return evaluate(milestone.copy(successPredicates = hardPredicates), plan, observation, targetPackage)
    }
}

data class PackagePolicy(
    val allowedPackages: MutableSet<String> = mutableSetOf(),
    val primaryPackage: String? = null,
    val allowSystemUi: Boolean = false,
    val allowTemporaryExternalPackages: Boolean = false,
) {
    fun allows(packageName: String, isSystemUi: Boolean = isSystemUiPackage(packageName)): Boolean {
        if (packageName.isBlank()) return false
        val normalized = packageName.lowercase()
        // Protected system surfaces are checked before the model-provided
        // allowlist.  An LLM cannot grant itself installer/permission access.
        if (isPermissionOrInstallerPackage(normalized)) return false
        if (isSystemUiPackage(normalized) || isSystemUi || normalized == "android") return allowSystemUi
        if (normalized in allowedPackages.map(String::lowercase).toSet()) return true
        return allowTemporaryExternalPackages && packageName != primaryPackage
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

data class GuardResult(val action: AgentAction?, val rejection: String? = null)

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
        if (milestone?.kind == TaskMilestoneKind.LAUNCH_APP &&
            packagePolicy.primaryPackage != null && observation.packageName != packagePolicy.primaryPackage
        ) {
            return GuardResult(AgentAction.LaunchApp(packagePolicy.primaryPackage))
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
                if (target == null) {
                    val editable = editableNodes(observation)
                    if (editable.count { it.focused } == 1) return GuardResult(action)
                    if (editable.size > 1) return GuardResult(null, "input_text target is ambiguous; provide nodeId or selector")
                    if (editable.isEmpty()) return GuardResult(null, "input_text has no editable target")
                }
            }

            is AgentAction.SubmitInput -> {
                val target = resolveEditable(observation, action.nodeId, action.target)
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
                if (current == action.desired) return GuardResult(null, "toggle already has the requested state")
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
        if (milestone?.kind == TaskMilestoneKind.LAUNCH_APP &&
            packagePolicy.primaryPackage != null && observation.packageName != packagePolicy.primaryPackage
        ) AgentAction.LaunchApp(packagePolicy.primaryPackage) else null

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
        if (action is AgentAction.Wait) return
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
