package com.androidagent.app.agent

import org.json.JSONObject

data class TaskMilestone(
    val id: String,
    val objective: String,
    val successPredicates: List<UiPredicate>,
    val kind: TaskMilestoneKind = TaskMilestoneKind.GENERIC,
) {
    val successEvidence: String get() = successPredicates.joinToString("; ") { it.description }
}

enum class TaskMilestoneKind { LAUNCH_APP, INPUT, INTERACTION, VERIFICATION, GENERIC }

enum class UiPredicateKind {
    PACKAGE_FOREGROUND,
    TEXT_PRESENT,
    EDITABLE_EQUALS,
    IME_HIDDEN,
    ELEMENT_PRESENT,
    ELEMENT_DISAPPEARED,
    ELEMENT_ENABLED,
    ELEMENT_SELECTED,
    ELEMENT_CHECKED,
    ELEMENT_TEXT_EQUALS,
    TOGGLE_STATE,
    /** Legacy input accepted only by the parser and normalized to TOGGLE_STATE. */
    @Deprecated("Use TOGGLE_STATE(expectedChecked=true)")
    TOGGLE_ON,
    /** Legacy fuzzy state predicate; new plans must not use it. */
    @Deprecated("Use a typed ELEMENT_* predicate")
    ELEMENT_STATE,
    SEMANTIC_CLAIM,
}

data class UiPredicate(
    val kind: UiPredicateKind,
    val valueRef: String? = null,
    val literal: String? = null,
    val description: String,
    /** Unique node contract for state predicates. */
    val target: ElementSelector? = null,
    /** Explicit package contract for PACKAGE_FOREGROUND. */
    val targetPackage: String? = null,
    /** Manager-stage description used before a concrete observation is available. */
    val targetHint: String? = null,
    /** Required checked value for TOGGLE_STATE. */
    val expectedChecked: Boolean? = null,
    val predicateId: String? = null,
)

class TaskPlanException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

object TaskPlanValidator {
    private val targetRequired = setOf(
        UiPredicateKind.TOGGLE_STATE,
        UiPredicateKind.EDITABLE_EQUALS,
        UiPredicateKind.ELEMENT_PRESENT,
        UiPredicateKind.ELEMENT_DISAPPEARED,
        UiPredicateKind.ELEMENT_ENABLED,
        UiPredicateKind.ELEMENT_SELECTED,
        UiPredicateKind.ELEMENT_CHECKED,
        UiPredicateKind.ELEMENT_TEXT_EQUALS,
    )

    fun requireValid(plan: TaskPlan): TaskPlan {
        val milestoneIds = plan.milestones.map { it.id }
        if (milestoneIds.any(String::isBlank) || milestoneIds.distinct().size != milestoneIds.size) {
            throw TaskPlanException("Milestone IDs must be unique and non-blank")
        }
        if (!plan.milestones.all { it.successPredicates.isNotEmpty() }) {
            throw TaskPlanException("Task plan contains a milestone without a verifiable predicate")
        }
        if (plan.milestones.any { milestone ->
            milestone.successPredicates.all { it.kind == UiPredicateKind.SEMANTIC_CLAIM }
        }) {
            throw TaskPlanException("Task plan contains a semantic-only milestone without a local verification condition")
        }
        val normalized = plan.copy(
            milestones = plan.milestones.map { milestone ->
                milestone.copy(
                    successPredicates = milestone.successPredicates.mapIndexed { index, predicate ->
                        predicate.copy(predicateId = predicate.predicateId ?: predicateIdFor(milestone.id, index))
                    },
                )
            },
        )
        val predicates = normalized.milestones.flatMap { it.successPredicates }
        val predicateIds = predicates.map { it.predicateId.orEmpty() }
        if (predicateIds.any(String::isBlank)) throw TaskPlanException("Every predicate must have a non-blank predicateId")
        if (predicateIds.distinct().size != predicateIds.size) {
            throw TaskPlanException("Predicate IDs must be unique across the task plan")
        }
        val safePredicateId = Regex("^[A-Za-z0-9_-]+$")
        predicates.forEach { predicate ->
            if (!safePredicateId.matches(predicate.predicateId.orEmpty())) {
                throw TaskPlanException("Predicate ID contains unsafe characters")
            }
            if (predicate.kind == UiPredicateKind.ELEMENT_STATE) {
                throw TaskPlanException("ELEMENT_STATE is ambiguous; use a typed ELEMENT_* predicate")
            }
            if (predicate.kind == UiPredicateKind.TOGGLE_ON) {
                throw TaskPlanException("TOGGLE_ON is legacy; use TOGGLE_STATE(expectedChecked=true)")
            }
            if (predicate.kind in targetRequired) {
                if (predicate.target == null && predicate.targetHint.isNullOrBlank()) {
                    throw TaskPlanException("${predicate.kind} requires a target description before binding")
                }
            }
            if (predicate.kind == UiPredicateKind.TOGGLE_STATE) {
                if (predicate.expectedChecked == null) throw TaskPlanException("TOGGLE_STATE requires expectedChecked")
            }
            if (predicate.kind == UiPredicateKind.PACKAGE_FOREGROUND) {
                val targetPackage = predicate.targetPackage ?: predicate.target?.packageName
                if (targetPackage.isNullOrBlank()) {
                    throw TaskPlanException("PACKAGE_FOREGROUND requires an explicit target package")
                }
                if (PackagePolicy.isProtectedPackage(targetPackage)) {
                    throw TaskPlanException("PACKAGE_FOREGROUND cannot target a protected system package")
                }
            }
        }
        return normalized
    }

    /** Replans must allocate a new ID when a predicate's meaning changes. */
    fun requireCompatiblePredicateIds(previous: TaskPlan, revised: TaskPlan) {
        val previousById = previous.milestones.flatMap { milestone ->
            milestone.successPredicates.mapIndexed { index, predicate ->
                (predicate.predicateId ?: predicateIdFor(milestone.id, index)) to predicate
            }
        }.toMap()
        revised.milestones.forEach { milestone ->
            milestone.successPredicates.forEachIndexed { index, predicate ->
                val id = predicate.predicateId ?: predicateIdFor(milestone.id, index)
                val old = previousById[id] ?: return@forEachIndexed
                val compatible = old.kind == predicate.kind &&
                    old.valueRef == predicate.valueRef &&
                    old.literal == predicate.literal &&
                    old.expectedChecked == predicate.expectedChecked &&
                    old.targetPackage == predicate.targetPackage &&
                    old.target == predicate.target &&
                    TargetHintMatcher.semanticallyEquivalent(old.targetHint, predicate.targetHint)
                if (!compatible) throw TaskPlanException("Predicate ID $id was reused for a different semantic contract")
            }
        }
    }

    fun predicateIdFor(milestoneId: String, index: Int): String =
        "${milestoneId.trim().ifBlank { "milestone" }.replace(Regex("[^A-Za-z0-9_-]"), "_")}-p${index + 1}"
}

data class TaskPlan(
    val summary: String,
    val targetAppHint: String,
    val goal: GoalContext,
    val milestones: List<TaskMilestone>,
    val allowedPackages: Set<String> = emptySet(),
) {
    init {
        require(milestones.isNotEmpty()) { "Task plan must contain milestones" }
    }

    val originalGoal: String get() = goal.originalGoal

    fun compactText(currentIndex: Int, bindings: PredicateBindingStore? = null): String = buildString {
        appendLine("summary=$summary")
        appendLine("targetApp=$targetAppHint")
        appendLine("allowedPackages=${allowedPackages.joinToString(",").ifBlank { "none" }}")
        appendLine("goal=${goal.originalGoal.take(4_000)}")
        milestones.forEachIndexed { index, milestone ->
            val status = when {
                index < currentIndex -> "completed"
                index == currentIndex -> "current"
                else -> "pending"
            }
            appendLine("${milestone.id} [$status/${milestone.kind}] ${milestone.objective}; evidence=${milestone.successEvidence}")
            milestone.successPredicates.forEachIndexed { predicateIndex, predicate ->
                // Keep the harness useful without echoing raw selector text or a
                // complete selector object into the model context.
                val target = predicate.target?.let { selector ->
                    "target=${TraceSanitizer.selectorMetadata(selector)}"
                }.orEmpty()
                val targetPackage = (predicate.targetPackage ?: predicate.target?.packageName)?.let { "targetPackage=$it" }.orEmpty()
                val targetHint = predicate.targetHint?.let { "targetHint=$it" }.orEmpty()
                val literal = predicate.literal?.let { "literal=${it.take(120)}" }.orEmpty()
                val valueRef = predicate.valueRef?.let { "valueRef=$it" }.orEmpty()
                val expectedChecked = predicate.expectedChecked?.let { "expectedChecked=$it" }.orEmpty()
                val binding = bindings?.get(milestone.id, predicateIndex)?.let { bound ->
                    val identityType = when {
                        bound.identity.viewIdResourceName != null -> "VIEW_ID"
                        bound.identity.treePath != null -> "TREE_PATH"
                        bound.identity.initialBounds != null -> "BOUNDS"
                        else -> "STABLE_KEY"
                    }
                    "binding=BOUND boundPackage=${bound.boundPackage} identityType=$identityType lifecycle=${bound.lifecycle}"
                } ?: "binding=UNBOUND"
                val predicateId = predicate.predicateId ?: TaskPlanValidator.predicateIdFor(milestone.id, predicateIndex)
                appendLine("  predicateId=$predicateId kind=${predicate.kind} description=${predicate.description.take(180)} $literal $valueRef $expectedChecked $targetPackage $targetHint $target $binding")
            }
        }
    }

    fun preserveCompletedPrefix(previous: TaskPlan, completedCount: Int): TaskPlan {
        val completed = previous.milestones.take(completedCount)
        val completedIds = completed.mapTo(mutableSetOf()) { it.id }
        val revisedPending = milestones.filterNot { it.id in completedIds }
        return copy(milestones = completed + revisedPending)
    }

    fun repairStartIndex(): Int = milestones.indexOfFirst {
        it.successPredicates.any { predicate -> predicate.kind in setOf(UiPredicateKind.SEMANTIC_CLAIM, UiPredicateKind.TOGGLE_STATE) }
    }.let { if (it >= 0) it else milestones.lastIndex.coerceAtLeast(0) }
}

enum class TransitionJudgement { NO_PROGRESS, PROGRESS, MILESTONE_COMPLETE }

data class CriticResult(val judgement: TransitionJudgement, val evidence: String)
data class VerificationResult(val done: Boolean, val reason: String)

object TaskPlanParser {
    fun parse(raw: String, goal: GoalContext): TaskPlan {
        val json = JSONObject(extractObject(raw))
        val milestonesJson = json.getJSONArray("milestones")
        val milestones = buildList {
            for (index in 0 until milestonesJson.length().coerceAtMost(8)) {
                val item = milestonesJson.getJSONObject(index)
                val objective = item.getString("objective").trim()
                require(objective.isNotBlank()) { "Milestone objective cannot be blank" }
                val milestoneId = item.optString("id", "m${index + 1}").ifBlank { "m${index + 1}" }
                add(
                    TaskMilestone(
                        id = milestoneId,
                        objective = objective,
                        successPredicates = parsePredicates(item, milestoneId),
                        kind = runCatching { TaskMilestoneKind.valueOf(item.optString("kind", "GENERIC").uppercase()) }
                            .getOrDefault(TaskMilestoneKind.GENERIC),
                    ),
                )
            }
        }
        require(milestones.map { it.id }.distinct().size == milestones.size) { "Milestone IDs must be unique" }
        val plan = TaskPlan(
            summary = json.optString("summary", goal.originalGoal).ifBlank { goal.originalGoal },
            targetAppHint = json.optString("targetAppHint", "").trim(),
            goal = goal,
            milestones = milestones,
            allowedPackages = json.optJSONArray("allowedPackages")?.let { array ->
                buildSet { for (index in 0 until array.length()) add(array.getString(index).trim()) }
            } ?: emptySet(),
        )
        return TaskPlanValidator.requireValid(plan)
    }

    fun parse(raw: String, goal: String): TaskPlan = parse(raw, ConservativeGoalInterpreter.interpret(goal))

    @Suppress("UNUSED_PARAMETER")
    fun parse(raw: String, goal: String, ignoredLegacyValue: String?): TaskPlan = parse(raw, goal)

    fun fallback(goal: GoalContext, targetAppHint: String): TaskPlan = throw TaskPlanException(
        "Manager plan unavailable after retries; refusing an unverifiable fallback plan",
    )

    fun fallback(goal: String, targetAppHint: String): TaskPlan = fallback(ConservativeGoalInterpreter.interpret(goal), targetAppHint)

    @Suppress("UNUSED_PARAMETER")
    fun fallback(goal: String, targetAppHint: String, ignoredLegacyValue: String?): TaskPlan = fallback(goal, targetAppHint)

    private fun extractObject(raw: String): String {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        require(start >= 0 && end >= start) { "Manager response did not contain JSON" }
        return raw.substring(start, end + 1)
    }

    private fun parsePredicates(item: JSONObject, milestoneId: String): List<UiPredicate> {
        val predicates = item.optJSONArray("successPredicates")
        if (predicates == null || predicates.length() == 0) {
            val evidence = item.optString("successEvidence", "Visible evidence confirms the milestone")
            return listOf(UiPredicate(UiPredicateKind.SEMANTIC_CLAIM, description = evidence))
        }
        return buildList {
            for (index in 0 until predicates.length()) {
                val predicate = predicates.getJSONObject(index)
                val rawKind = UiPredicateKind.valueOf(predicate.getString("kind").uppercase())
                require(rawKind != UiPredicateKind.ELEMENT_STATE) {
                    "ELEMENT_STATE is ambiguous; use a typed ELEMENT_* predicate"
                }
                val legacyToggle = rawKind == UiPredicateKind.TOGGLE_ON
                val kind = if (legacyToggle) UiPredicateKind.TOGGLE_STATE else rawKind
                val valueRef = predicate.optString("valueRef").trim().ifBlank { null }
                val literal = predicate.optString("literal").trim().ifBlank { null }
                require(valueRef == null || valueRef == "goal_text") { "Unknown predicate valueRef" }
                if (kind in setOf(UiPredicateKind.TEXT_PRESENT, UiPredicateKind.EDITABLE_EQUALS, UiPredicateKind.ELEMENT_TEXT_EQUALS)) {
                    require(valueRef != null || literal != null) { "$kind requires a value" }
                }
                val target = ElementSelectorJson.parse(predicate.optJSONObject("target"))
                val targetPackage = predicate.optString("targetPackage").trim().ifBlank {
                    predicate.optString("packageName").trim().ifBlank { target?.packageName }
                }
                val targetHint = predicate.optString("targetHint").trim().ifBlank {
                    predicate.optString("targetDescription").trim().ifBlank { null }
                }
                add(
                    UiPredicate(
                        kind = kind,
                        valueRef = valueRef,
                        literal = literal,
                        description = predicate.optString("description", kind.name).trim().ifBlank { kind.name },
                        target = target,
                        targetPackage = targetPackage,
                        targetHint = targetHint,
                        expectedChecked = if (legacyToggle) true else if (predicate.has("expectedChecked")) predicate.optBoolean("expectedChecked") else null,
                        predicateId = predicate.optString("predicateId").trim().ifBlank {
                            TaskPlanValidator.predicateIdFor(milestoneId, index)
                        },
                    ),
                )
            }
        }
    }
}
