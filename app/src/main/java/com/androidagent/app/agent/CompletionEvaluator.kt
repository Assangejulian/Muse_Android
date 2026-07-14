package com.androidagent.app.agent

data class CompletionResult(
    val completed: Boolean,
    val confidence: Float,
    val evidence: List<String>,
    val reason: String,
)

interface CompletionEvaluator {
    fun evaluate(
        goal: GoalContext,
        history: List<ActionRecord>,
        before: Observation?,
        after: Observation,
    ): CompletionResult
}

/** Conservative local evaluator. A state change alone is never completion proof. */
object LocalCompletionEvaluator : CompletionEvaluator {
    override fun evaluate(
        goal: GoalContext,
        history: List<ActionRecord>,
        before: Observation?,
        after: Observation,
    ): CompletionResult {
        val evidence = mutableListOf<String>()
        if (goal.explicitAppHint != null && after.packageName == goal.explicitAppHint) {
            evidence += "foreground package matches the explicit app hint"
        }
        val last = history.lastOrNull()
        if (last?.success == true && last.result.startsWith("evidence:", ignoreCase = true)) {
            evidence += last.result.removePrefix("evidence:").trim()
        }
        val changed = before != null && before.observationId != after.observationId
        return when {
            evidence.any { it.isNotBlank() } && last?.success == true ->
                CompletionResult(true, 0.8f, evidence, "direct local evidence is present")
            else -> CompletionResult(false, if (changed) 0.2f else 0f, evidence, "completion evidence is insufficient")
        }
    }
}
