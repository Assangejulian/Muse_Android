package com.androidagent.app.agent

import org.json.JSONArray
import org.json.JSONObject

data class TaskMilestone(
    val id: String,
    val objective: String,
    val successPredicates: List<UiPredicate>,
) {
    val successEvidence: String get() = successPredicates.joinToString("; ") { it.description }
}

enum class UiPredicateKind { PACKAGE_FOREGROUND, TEXT_PRESENT, EDITABLE_EQUALS, TOGGLE_ON, SEMANTIC_CLAIM }

data class UiPredicate(
    val kind: UiPredicateKind,
    val valueRef: String? = null,
    val literal: String? = null,
    val description: String,
)

data class TaskPlan(
    val summary: String,
    val targetAppHint: String,
    val canonicalQuery: String?,
    val milestones: List<TaskMilestone>,
) {
    init {
        require(milestones.isNotEmpty()) { "Task plan must contain milestones" }
    }

    fun compactText(currentIndex: Int): String = buildString {
        appendLine("summary=$summary")
        appendLine("targetApp=$targetAppHint")
        appendLine("canonicalQuery=${canonicalQuery ?: "none"}")
        milestones.forEachIndexed { index, milestone ->
            val status = when {
                index < currentIndex -> "completed"
                index == currentIndex -> "current"
                else -> "pending"
            }
            appendLine("${milestone.id} [$status] ${milestone.objective}; evidence=${milestone.successEvidence}")
        }
    }

    fun preserveCompletedPrefix(previous: TaskPlan, completedCount: Int): TaskPlan {
        val completed = previous.milestones.take(completedCount)
        val completedIds = completed.mapTo(mutableSetOf()) { it.id }
        val revisedPending = milestones.filterNot { it.id in completedIds }
        return copy(milestones = completed + revisedPending)
    }

    fun repairStartIndex(): Int {
        val semanticIndex = milestones.indexOfFirst { milestone ->
            milestone.successPredicates.any { it.kind == UiPredicateKind.SEMANTIC_CLAIM || it.kind == UiPredicateKind.TOGGLE_ON }
        }
        return if (semanticIndex >= 0) semanticIndex else milestones.lastIndex.coerceAtLeast(0)
    }
}

enum class TransitionJudgement { NO_PROGRESS, PROGRESS, MILESTONE_COMPLETE }

data class CriticResult(val judgement: TransitionJudgement, val evidence: String)

data class VerificationResult(val done: Boolean, val reason: String)

object TaskPlanParser {
    fun parse(raw: String, goal: String, canonicalQuery: String?): TaskPlan {
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
                        successPredicates = parsePredicates(item, canonicalQuery),
                    ),
                )
            }
        }
        require(milestones.map { it.id }.distinct().size == milestones.size) { "Milestone IDs must be unique" }
        return TaskPlan(
            summary = json.optString("summary", goal).ifBlank { goal },
            targetAppHint = json.optString("targetAppHint", "").trim(),
            canonicalQuery = canonicalQuery,
            milestones = milestones,
        )
    }

    fun fallback(goal: String, targetAppHint: String, canonicalQuery: String?): TaskPlan {
        val milestones = buildList {
            add(TaskMilestone("m1", "Launch the requested target app", listOf(UiPredicate(UiPredicateKind.PACKAGE_FOREGROUND, description = "The foreground package is the target app"))))
            if (canonicalQuery != null) {
                add(TaskMilestone("m2", "Open the app search interface and enter the canonical query exactly", listOf(UiPredicate(UiPredicateKind.EDITABLE_EQUALS, valueRef = "canonical_query", description = "The search field exactly equals the canonical query"))))
                add(TaskMilestone("m3", "Open the result that exactly matches the requested entity", listOf(UiPredicate(UiPredicateKind.SEMANTIC_CLAIM, valueRef = "canonical_query", description = "The requested entity page or matching result is visibly open"))))
            }
            if (goal.contains("最新")) add(TaskMilestone("m4", "Open the newest requested content", listOf(UiPredicate(UiPredicateKind.SEMANTIC_CLAIM, description = "The requested newest content is open"))))
            val finalPredicate = if (goal.contains("点赞")) {
                UiPredicate(UiPredicateKind.TOGGLE_ON, literal = "like", description = "The like control is visibly in the ON state")
            } else {
                UiPredicate(UiPredicateKind.SEMANTIC_CLAIM, description = "The screen visibly proves the requested result")
            }
            add(TaskMilestone("m${size + 1}", "Perform the final requested interaction", listOf(finalPredicate)))
        }
        return TaskPlan(goal, targetAppHint, canonicalQuery, milestones)
    }

    private fun extractObject(raw: String): String {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        require(start >= 0 && end >= start) { "Manager response did not contain JSON" }
        return raw.substring(start, end + 1)
    }

    private fun parsePredicates(item: JSONObject, canonicalQuery: String?): List<UiPredicate> {
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
                require(valueRef == null || valueRef == "canonical_query") { "Unknown predicate valueRef" }
                if (valueRef == "canonical_query") require(canonicalQuery != null) { "canonical_query is unavailable" }
                if (kind == UiPredicateKind.TEXT_PRESENT || kind == UiPredicateKind.EDITABLE_EQUALS) {
                    require(valueRef != null || literal != null) { "$kind requires a value" }
                }
                if (kind == UiPredicateKind.TOGGLE_ON) require(literal != null) { "TOGGLE_ON requires a semantic target" }
                add(UiPredicate(kind, valueRef, literal, predicate.optString("description", kind.name).trim().ifBlank { kind.name }))
            }
        }
    }
}
