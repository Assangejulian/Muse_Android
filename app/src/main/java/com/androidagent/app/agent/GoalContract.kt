package com.androidagent.app.agent

/**
 * A lossless, deliberately conservative representation of a user goal.
 * The core runtime must not guess a workflow from natural-language keywords.
 */
data class GoalContext(
    val originalGoal: String,
    val explicitAppHint: String? = null,
    val constraints: List<String> = emptyList(),
    val requestedOutcome: String? = null,
)

typealias ExecutionGoal = GoalContext

interface GoalInterpreter {
    fun interpret(goal: String): GoalContext
}

object ConservativeGoalInterpreter : GoalInterpreter {
    override fun interpret(goal: String): GoalContext = GoalContext(originalGoal = goal)
}

/** Compatibility facade for callers that used the old contract object. */
internal object GoalContract {
    fun interpret(goal: String): GoalContext = ConservativeGoalInterpreter.interpret(goal)
}
