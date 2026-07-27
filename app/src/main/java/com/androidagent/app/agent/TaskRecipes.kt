package com.androidagent.app.agent

/**
 * Optional deterministic extensions around the generic agent loop.
 *
 * Muse is intentionally model-first: multi-step UI workflows (search vs ranking,
 * which list item, comments, likes, …) are planned by the Manager/Actor from the
 * live observation and app catalog. Hardcoded app recipes used to hijack that
 * path (e.g. forcing search-box input from NLP heuristics) and are not registered.
 *
 * Keep this registry empty unless a future recipe is truly app-agnostic, proven
 * by local predicates, and never invents user values from natural language.
 */
internal interface TaskRecipe {
    val id: String

    fun normalizePlan(plan: TaskPlan): TaskPlan = plan

    fun requiredAction(observation: Observation, milestone: TaskMilestone): AgentAction? = null

    fun rejectAction(action: AgentAction, observation: Observation, milestone: TaskMilestone): String? = null
}

internal object TaskRecipeRegistry {
    /**
     * Always null in the stock build. Call sites may keep the hook for optional
     * future plugins without reintroducing goal-string NLP recipes.
     */
    @Suppress("UNUSED_PARAMETER")
    fun select(goal: GoalContext, targetPackage: String?): TaskRecipe? = null
}
