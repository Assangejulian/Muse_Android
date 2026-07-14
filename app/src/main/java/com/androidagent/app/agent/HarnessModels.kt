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
    ELEMENT_STATE,
    TOGGLE_ON,
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
)

class TaskPlanException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

object TaskPlanValidator {
    private val targetRequired = setOf(
        UiPredicateKind.TOGGLE_ON,
        UiPredicateKind.EDITABLE_EQUALS,
        UiPredicateKind.ELEMENT_STATE,
    )

    fun requireValid(plan: TaskPlan): TaskPlan {
        if (!plan.milestones.all { it.successPredicates.isNotEmpty() }) {
            throw TaskPlanException("Task plan contains a milestone without a verifiable predicate")
        }
        if (plan.milestones.any { milestone ->
            milestone.successPredicates.all { it.kind == UiPredicateKind.SEMANTIC_CLAIM }
        }) {
            throw TaskPlanException("Task plan contains a semantic-only milestone without a local verification condition")
        }
        plan.milestones.flatMap { it.successPredicates }.forEach { predicate ->
            if (predicate.kind in targetRequired) {
                if (predicate.target == null) throw TaskPlanException("${predicate.kind} requires a unique target selector")
            }
            if (predicate.kind == UiPredicateKind.PACKAGE_FOREGROUND) {
                if (predicate.targetPackage.isNullOrBlank() && predicate.target?.packageName.isNullOrBlank()) {
                    throw TaskPlanException("PACKAGE_FOREGROUND requires an explicit target package")
                }
            }
        }
        return plan
    }
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

    fun compactText(currentIndex: Int): String = buildString {
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
            milestone.successPredicates.forEach { predicate ->
                val target = predicate.target?.let { selector ->
                    "target=${selector.viewIdResourceName ?: selector.text ?: selector.description ?: selector.bounds ?: "selector"}"
                }.orEmpty()
                val targetPackage = predicate.targetPackage?.let { "targetPackage=$it" }.orEmpty()
                if (target.isNotBlank() || targetPackage.isNotBlank()) appendLine("  ${predicate.kind} $target $targetPackage")
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
        it.successPredicates.any { predicate -> predicate.kind in setOf(UiPredicateKind.SEMANTIC_CLAIM, UiPredicateKind.TOGGLE_ON) }
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
                add(
                    TaskMilestone(
                        id = item.optString("id", "m${index + 1}").ifBlank { "m${index + 1}" },
                        objective = objective,
                        successPredicates = parsePredicates(item),
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

    private fun parsePredicates(item: JSONObject): List<UiPredicate> {
        val predicates = item.optJSONArray("successPredicates")
        if (predicates == null || predicates.length() == 0) {
            val evidence = item.optString("successEvidence", "Visible evidence confirms the milestone")
            return listOf(UiPredicate(UiPredicateKind.SEMANTIC_CLAIM, description = evidence))
        }
        return buildList {
            for (index in 0 until predicates.length()) {
                val predicate = predicates.getJSONObject(index)
                val kind = UiPredicateKind.valueOf(predicate.getString("kind").uppercase())
                val valueRef = predicate.optString("valueRef").trim().ifBlank { null }
                val literal = predicate.optString("literal").trim().ifBlank { null }
                require(valueRef == null || valueRef == "goal_text") { "Unknown predicate valueRef" }
                if (kind in setOf(UiPredicateKind.TEXT_PRESENT, UiPredicateKind.EDITABLE_EQUALS, UiPredicateKind.ELEMENT_STATE)) {
                    require(valueRef != null || literal != null) { "$kind requires a value" }
                }
                val target = ElementSelectorJson.parse(predicate.optJSONObject("target"))
                val targetPackage = predicate.optString("targetPackage").trim().ifBlank {
                    predicate.optString("packageName").trim().ifBlank { target?.packageName }
                }
                add(
                    UiPredicate(
                        kind = kind,
                        valueRef = valueRef,
                        literal = literal,
                        description = predicate.optString("description", kind.name).trim().ifBlank { kind.name },
                        target = target,
                        targetPackage = targetPackage,
                    ),
                )
            }
        }
    }
}
