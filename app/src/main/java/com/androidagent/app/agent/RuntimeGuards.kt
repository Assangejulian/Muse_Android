package com.androidagent.app.agent

data class PredicateEvidence(val proven: Boolean, val details: List<String>)

object MilestoneEvaluator {
    fun evaluate(milestone: TaskMilestone, plan: TaskPlan, observation: Observation, targetPackage: String?): PredicateEvidence {
        val details = mutableListOf<String>()
        val results = milestone.successPredicates.map { predicate ->
            val value = when (predicate.valueRef) {
                "canonical_query" -> plan.canonicalQuery
                else -> predicate.literal
            }
            val proven = when (predicate.kind) {
                UiPredicateKind.PACKAGE_FOREGROUND -> targetPackage != null && observation.packageName == targetPackage
                UiPredicateKind.TEXT_PRESENT -> value != null && observation.nodes.any {
                    it.text.equals(value, true) || it.description.equals(value, true)
                }
                UiPredicateKind.EDITABLE_EQUALS -> value != null && observation.nodes.any {
                    it.editable && it.text == value
                }
                UiPredicateKind.TOGGLE_ON -> observation.nodes.any { node ->
                    val semanticLabel = "${node.text} ${node.description} ${node.viewId}".lowercase()
                    val matchesTarget = when (predicate.literal?.lowercase()) {
                        "like" -> semanticLabel.contains("点赞") || semanticLabel.contains("like")
                        null, "" -> false
                        else -> semanticLabel.contains(predicate.literal.orEmpty().lowercase())
                    }
                    matchesTarget && (node.checked == true || node.selected || node.description.contains("已点赞") || node.description.contains("取消点赞"))
                }
                UiPredicateKind.SEMANTIC_CLAIM -> false
            }
            details += "${predicate.kind}=${if (proven) "PROVEN" else "UNKNOWN"}: ${predicate.description}"
            proven
        }
        return PredicateEvidence(results.isNotEmpty() && results.all { it }, details)
    }

    fun evaluateHardPredicates(milestone: TaskMilestone, plan: TaskPlan, observation: Observation, targetPackage: String?): PredicateEvidence {
        val hardPredicates = milestone.successPredicates.filter {
            it.kind == UiPredicateKind.PACKAGE_FOREGROUND ||
                it.kind == UiPredicateKind.TEXT_PRESENT ||
                it.kind == UiPredicateKind.EDITABLE_EQUALS
        }
        if (hardPredicates.isEmpty()) return PredicateEvidence(true, listOf("No unresolved hard predicates"))
        return evaluate(milestone.copy(successPredicates = hardPredicates), plan, observation, targetPackage)
    }
}

data class GuardResult(val action: AgentAction?, val rejection: String? = null)

class ToolGuard(private val plan: TaskPlan) {
    fun normalizeAndValidate(action: AgentAction, observation: Observation): GuardResult {
        val normalized = when (action) {
            is AgentAction.InputText -> {
                val locked = plan.canonicalQuery
                if (locked != null) AgentAction.InputText(locked, action.nodeId) else action
            }
            is AgentAction.ClickText -> {
                if (plan.canonicalQuery != null && action.text.matches(Regex("\\d")) && observation.nodes.any { it.editable || it.focused }) {
                    return GuardResult(null, "single keyboard digit is unrelated to the locked query")
                }
                action
            }
            is AgentAction.ClickNode -> action
            else -> action
        }
        if (normalized is AgentAction.ClickNode && plan.canonicalQuery != null) {
            val target = observation.nodes.firstOrNull { it.id == normalized.nodeId }
            val label = "${target?.text.orEmpty()} ${target?.description.orEmpty()}".trim()
            if (label.matches(Regex("\\d")) && observation.nodes.any { it.editable || it.focused }) {
                return GuardResult(null, "keyboard digit is unrelated to the locked query")
            }
        }
        if (normalized is AgentAction.ClickNode && observation.nodes.none { it.id == normalized.nodeId }) {
            return GuardResult(null, "node is not part of the bound observation")
        }
        if (normalized is AgentAction.ClickText && normalized.text.isBlank()) {
            return GuardResult(null, "click_text requires non-blank visible text")
        }
        if (normalized is AgentAction.InputText) {
            val editableNodes = observation.nodes.filter { it.editable && it.enabled }
            if (normalized.nodeId != null && editableNodes.none { it.id == normalized.nodeId }) {
                return GuardResult(null, "input target is not an editable element in the bound observation")
            }
            if (normalized.nodeId == null && editableNodes.size != 1) {
                return GuardResult(null, "input_text requires nodeId when the editable target is ambiguous")
            }
        }
        if (normalized is AgentAction.SubmitInput && normalized.nodeId != null && observation.nodes.none { it.id == normalized.nodeId && it.editable }) {
            return GuardResult(null, "submit target is not an editable element in the bound observation")
        }
        if (normalized is AgentAction.EnsureToggle && observation.nodes.none { it.id == normalized.nodeId }) {
            return GuardResult(null, "toggle target is not part of the bound observation")
        }
        if (normalized is AgentAction.Home) return GuardResult(null, "Home is not an allowed recovery strategy inside a locked app task")
        return GuardResult(normalized)
    }
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

    fun blockRepeated(action: AgentAction, observation: Observation): String? {
        val milestone = currentMilestone ?: return null
        val key = "${milestone.id}|${observation.stateFingerprint()}|${semanticAction(action)}"
        val count = attempts.getOrDefault(key, 0)
        if (count >= 2) return "strategy exhausted for the same milestone and screen"
        attempts[key] = count + 1
        return null
    }

    fun record(trace: StepTrace) {
        traces += trace
        if (trace.judgement == TransitionJudgement.NO_PROGRESS) noProgressCount += 1 else noProgressCount = 0
    }

    fun advance(evidence: String): String {
        val completed = currentMilestone?.id ?: "none"
        this.evidence[completed] = evidence
        currentMilestoneIndex += 1
        noProgressCount = 0
        return "$completed proven: $evidence"
    }

    fun cyclePeriod(): Int? {
        val trail = fingerprints.toList()
        for (period in 1..4) {
            if (trail.size >= period * 2 && trail.takeLast(period) == trail.dropLast(period).takeLast(period)) return period
        }
        return null
    }

    fun recentFailureContext(): String = traces.takeLast(8).joinToString("\n") {
        "${it.milestoneId}: ${it.action} -> ${it.judgement} (${it.evidence})"
    }

    fun planText(): String = plan.compactText(currentMilestoneIndex)

    fun evidenceSummary(): String = if (evidence.isEmpty()) {
        "No milestone evidence recorded"
    } else {
        evidence.entries.joinToString("\n") { (id, proof) -> "$id: $proof" }
    }

    private fun semanticAction(action: AgentAction): String = when (action) {
        is AgentAction.ClickNode -> "tap:${action.nodeId}"
        is AgentAction.ClickText -> "tap_text:${action.text.lowercase()}"
        is AgentAction.TapPoint -> "tap_point:${action.x / 25}:${action.y / 25}"
        is AgentAction.InputText -> "set_text:${action.nodeId}:${action.text.lowercase()}"
        is AgentAction.SubmitInput -> "submit:${action.nodeId}"
        is AgentAction.EnsureToggle -> "ensure_toggle:${action.nodeId}:${action.desired}"
        is AgentAction.LaunchApp -> "launch:${action.packageName}"
        is AgentAction.Swipe -> "scroll:${action.direction}"
        AgentAction.Back -> "back"
        AgentAction.Home -> "home"
        else -> action::class.simpleName.orEmpty()
    }
}
