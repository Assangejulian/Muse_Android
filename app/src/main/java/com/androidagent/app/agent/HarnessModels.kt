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
)

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
        return TaskPlan(
            summary = json.optString("summary", goal.originalGoal).ifBlank { goal.originalGoal },
            targetAppHint = json.optString("targetAppHint", "").trim(),
            goal = goal,
            milestones = milestones,
            allowedPackages = json.optJSONArray("allowedPackages")?.let { array ->
                buildSet { for (index in 0 until array.length()) add(array.getString(index).trim()) }
            } ?: emptySet(),
        )
    }

    fun parse(raw: String, goal: String): TaskPlan = parse(raw, ConservativeGoalInterpreter.interpret(goal))

    @Suppress("UNUSED_PARAMETER")
    fun parse(raw: String, goal: String, ignoredLegacyValue: String?): TaskPlan = parse(raw, goal)

    fun fallback(goal: GoalContext, targetAppHint: String): TaskPlan = TaskPlan(
        summary = goal.originalGoal,
        targetAppHint = targetAppHint,
        goal = goal,
        milestones = buildList {
            if (targetAppHint.isNotBlank()) add(
                TaskMilestone(
                    id = "launch",
                    objective = "Bring the explicitly selected application to the foreground",
                    successPredicates = listOf(UiPredicate(UiPredicateKind.PACKAGE_FOREGROUND, description = "The selected package is foreground")),
                    kind = TaskMilestoneKind.LAUNCH_APP,
                ),
            )
            add(
                TaskMilestone(
                    id = "verify",
                    objective = "Perform the requested interaction and establish direct observable evidence",
                    successPredicates = listOf(UiPredicate(UiPredicateKind.SEMANTIC_CLAIM, description = "The current screen directly proves the requested outcome")),
                    kind = TaskMilestoneKind.VERIFICATION,
                ),
            )
        },
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
                add(UiPredicate(kind, valueRef, literal, predicate.optString("description", kind.name).trim().ifBlank { kind.name }))
            }
        }
    }
}
