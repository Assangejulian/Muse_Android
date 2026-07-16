package com.androidagent.app.agent

import java.util.Locale

/** A narrow deterministic extension around the generic agent loop. */
internal interface TaskRecipe {
    val id: String

    fun normalizePlan(plan: TaskPlan): TaskPlan = plan

    fun requiredAction(observation: Observation, milestone: TaskMilestone): AgentAction? = null

    fun rejectAction(action: AgentAction, observation: Observation, milestone: TaskMilestone): String? = null
}

internal object TaskRecipeRegistry {
    fun select(goal: GoalContext, targetPackage: String?): TaskRecipe? {
        val query = SearchGoalParser.extract(goal.originalGoal) ?: return null
        return SearchTaskRecipe(
            query = query,
            bilibiliGuards = targetPackage == BILIBILI_PACKAGE || SearchGoalParser.referencesBilibili(goal.originalGoal),
            requiresLike = goal.originalGoal.contains("点赞") || goal.originalGoal.contains("like", true),
            requiresLatest = goal.originalGoal.contains("最新") || goal.originalGoal.contains("latest", true) || goal.originalGoal.contains("newest", true),
        )
    }

    const val BILIBILI_PACKAGE = "tv.danmaku.bili"
}

internal object SearchGoalParser {
    private val latestEntity = Regex("(?:给|搜索|查找|搜)(.+?)(?:的)?最新")
    private val chineseSearch = Regex(
        "(?:搜索|查找|搜一下|搜|查询)(?:一下)?[：: ]*[“\"']?" +
            "(.+?)(?=[”\"'，。,.；;]|(?:然后|接着|之后|并且|并)(?:再)?(?:打开|播放|点击|点赞|收藏|关注|转发|评论)|$)",
    )
    private val englishSearch = Regex("(?i)\\bsearch(?:\\s+for)?\\s+(.+?)(?:\\s+(?:in|on|and)\\b|[,.]|$)")

    fun extract(goal: String): String? {
        val raw = latestEntity.find(goal)?.groupValues?.getOrNull(1)
            ?: chineseSearch.find(goal)?.groupValues?.getOrNull(1)
            ?: englishSearch.find(goal)?.groupValues?.getOrNull(1)
        return raw
            ?.trim()
            ?.trim('“', '”', '\"', '\'', ' ')
            ?.removePrefix("一下")
            ?.removePrefix("用户")
            ?.removePrefix("UP主")
            ?.replace(Regex("(?:的)?最新.*$"), "")
            ?.trim()
            ?.take(120)
            ?.takeIf(String::isNotBlank)
    }

    fun referencesBilibili(goal: String): Boolean {
        val normalized = goal.lowercase(Locale.ROOT)
        return listOf("b站", "哔哩哔哩", "bilibili").any(normalized::contains)
    }
}

private class SearchTaskRecipe(
    private val query: String,
    private val bilibiliGuards: Boolean,
    private val requiresLike: Boolean,
    private val requiresLatest: Boolean,
) : TaskRecipe {
    override val id: String = "search"

    override fun normalizePlan(plan: TaskPlan): TaskPlan {
        if (plan.milestones.any { it.id == INPUT_MILESTONE_ID || it.id == SUBMIT_MILESTONE_ID }) return plan

        val withoutGuessedSearchInput = plan.milestones.filterNot { milestone ->
            milestone.kind == TaskMilestoneKind.INPUT && looksLikeSearchObjective(milestone.objective)
        }
        val insertionIndex = withoutGuessedSearchInput.indexOfLast { it.kind == TaskMilestoneKind.LAUNCH_APP }
            .let { if (it >= 0) it + 1 else 0 }
        val recipeMilestones = listOf(
            TaskMilestone(
                id = INPUT_MILESTONE_ID,
                objective = "Enter the exact user-provided search query",
                successPredicates = listOf(
                    UiPredicate(
                        kind = UiPredicateKind.EDITABLE_EQUALS,
                        literal = query,
                        description = "The search input exactly equals the requested query",
                        targetHint = "search query input 搜索 输入框",
                        predicateId = INPUT_PREDICATE_ID,
                    ),
                ),
                kind = TaskMilestoneKind.INPUT,
            ),
            TaskMilestone(
                id = SUBMIT_MILESTONE_ID,
                objective = "Submit the exact search query and dismiss the input method",
                successPredicates = listOf(
                    UiPredicate(
                        kind = UiPredicateKind.IME_HIDDEN,
                        description = "The input method is hidden after search submission",
                        predicateId = SUBMIT_PREDICATE_ID,
                    ),
                ),
                kind = TaskMilestoneKind.INTERACTION,
            ),
        )
        val normalized = withoutGuessedSearchInput.toMutableList().apply {
            addAll(insertionIndex.coerceIn(0, size), recipeMilestones)
        }
        return TaskPlanValidator.requireValid(plan.copy(milestones = normalized))
    }

    override fun requiredAction(observation: Observation, milestone: TaskMilestone): AgentAction? = when (milestone.id) {
        INPUT_MILESTONE_ID -> inputAction(observation)
        SUBMIT_MILESTONE_ID -> submitAction(observation)
        else -> null
    }

    override fun rejectAction(action: AgentAction, observation: Observation, milestone: TaskMilestone): String? {
        if (milestone.id in setOf(INPUT_MILESTONE_ID, SUBMIT_MILESTONE_ID) && action is AgentAction.TapPoint) {
            return "search recipe requires an accessibility-bound search target"
        }
        if (!bilibiliGuards) return null

        val target = TargetResolver.resolve(action, observation)
        val context = target?.let { semanticContext(it, observation) }.orEmpty()
        if (context.isNotBlank() && DISTRACTION_TERMS.any { context.contains(it, true) } && !context.contains(query, true)) {
            return "Bilibili recipe rejected a hot-search, recommendation, ad, or unrelated result"
        }
        if (requiresLatest && (context.contains("置顶") || context.contains("pinned", true))) {
            return "Bilibili recipe rejected pinned content for a latest-content goal"
        }
        if (requiresLike && action is AgentAction.EnsureToggle) {
            val label = target?.let { "${it.text} ${it.description} ${it.viewId}" }.orEmpty()
            if (!label.contains("点赞") && !label.contains("like", true)) {
                return "Bilibili recipe requires the final toggle to be the like control"
            }
        }
        return null
    }

    private fun inputAction(observation: Observation): AgentAction? {
        val editable = searchEditable(observation)
        if (editable != null) {
            if (editable.text == query) return null
            return AgentAction.InputText(
                text = query,
                nodeId = editable.id,
                target = NodeSelector.from(editable),
                mode = InputMode.REPLACE,
                submit = false,
                predicateId = INPUT_PREDICATE_ID,
            )
        }

        val entries = observation.nodes.filter { node ->
            node.visible && node.enabled && node.clickable && isSearchEntry(node) && !isSearchDistraction(node)
        }
        return entries.singleOrNull()?.let { node ->
            AgentAction.ClickNode(node.id, selector = NodeSelector.from(node))
        }
    }

    private fun submitAction(observation: Observation): AgentAction? {
        if (!observation.imeVisible) return null
        val editable = observation.nodes.filter { node ->
            node.visible && node.enabled && node.editable && !node.password && !node.isInputMethod && node.text == query
        }.singleOrNull() ?: searchEditable(observation)?.takeIf { it.text == query } ?: return null
        return AgentAction.SubmitInput(
            nodeId = editable.id,
            target = NodeSelector.from(editable),
        )
    }

    private fun searchEditable(observation: Observation): UiNodeSnapshot? {
        val editables = observation.nodes.filter { node ->
            node.visible && node.enabled && node.editable && !node.password && !node.isInputMethod
        }
        val semantic = editables.filter(::isSearchEntry)
        return when {
            semantic.size == 1 -> semantic.single()
            editables.count { it.focused } == 1 -> editables.single { it.focused }
            editables.size == 1 -> editables.single()
            else -> null
        }
    }

    private fun isSearchEntry(node: UiNodeSnapshot): Boolean {
        val label = "${node.text} ${node.description} ${node.viewId} ${node.className}".lowercase(Locale.ROOT)
        return label.contains("search") || label.contains("搜索") || label.contains("查找")
    }

    private fun isSearchDistraction(node: UiNodeSnapshot): Boolean {
        val label = "${node.text} ${node.description} ${node.viewId}".lowercase(Locale.ROOT)
        return DISTRACTION_TERMS.any { label.contains(it, true) }
    }

    private fun semanticContext(target: UiNodeSnapshot, observation: Observation): String {
        val targetPath = target.treePath
        val parentPath = targetPath?.dropLast(1)
        val related = observation.nodes.filter { node ->
            node.packageName == target.packageName && node.windowId == target.windowId && when {
                parentPath.isNullOrEmpty() || node.treePath == null -> node.id == target.id
                else -> node.treePath.take(parentPath.size) == parentPath
            }
        }
        return (related + target).distinctBy { it.id }
            .joinToString(" ") { "${it.text} ${it.description} ${it.viewId}" }
            .take(2_000)
    }

    private fun looksLikeSearchObjective(value: String): Boolean {
        val normalized = value.lowercase(Locale.ROOT)
        return listOf("search", "query", "搜索", "查找", "输入关键词").any(normalized::contains)
    }

    private companion object {
        const val INPUT_MILESTONE_ID = "recipe_search_input"
        const val INPUT_PREDICATE_ID = "recipe_search_input-p1"
        const val SUBMIT_MILESTONE_ID = "recipe_search_submit"
        const val SUBMIT_PREDICATE_ID = "recipe_search_submit-p1"
        val DISTRACTION_TERMS = listOf(
            "热搜", "大家都在搜", "推荐", "广告", "推广", "直播", "番剧",
            "hot_search", "trending", "suggestion", "recommend", "advert", "sponsor",
        )
    }
}
