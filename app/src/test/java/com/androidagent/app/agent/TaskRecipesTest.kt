package com.androidagent.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskRecipesTest {
    @Test
    fun extractsCreatorFromLatestBilibiliGoal() {
        assertEquals("老番茄", SearchGoalParser.extract("打开B站给老番茄的最新视频点赞"))
        assertTrue(SearchGoalParser.referencesBilibili("在哔哩哔哩查找内容"))
    }

    @Test
    fun excludesFollowUpActionFromSearchQuery() {
        assertEquals("老番茄", SearchGoalParser.extract("在B站搜索老番茄并点赞"))
        assertEquals("猫咪视频", SearchGoalParser.extract("搜索猫咪视频，然后播放第一个结果"))
    }

    @Test
    fun resolvesBilibiliFromCommonAliasEvenWhenConfiguredHintIsNotTheLabel() {
        val apps = listOf("哔哩哔哩" to TaskRecipeRegistry.BILIBILI_PACKAGE)

        assertEquals(TaskRecipeRegistry.BILIBILI_PACKAGE, resolveTargetPackage("B站", "打开它", apps))
        assertEquals(TaskRecipeRegistry.BILIBILI_PACKAGE, resolveTargetPackage("", "打开B站", apps))
    }

    @Test
    fun recipeAddsStableInputAndSubmitContractsOnce() {
        val recipe = TaskRecipeRegistry.select(
            GoalContext("在B站搜索老番茄"),
            TaskRecipeRegistry.BILIBILI_PACKAGE,
        )!!
        val original = TaskPlan(
            summary = "search",
            targetAppHint = "Bilibili",
            goal = GoalContext("在B站搜索老番茄"),
            milestones = listOf(
                TaskMilestone(
                    id = "launch",
                    objective = "launch",
                    successPredicates = listOf(
                        UiPredicate(
                            UiPredicateKind.PACKAGE_FOREGROUND,
                            targetPackage = TaskRecipeRegistry.BILIBILI_PACKAGE,
                            description = "foreground",
                            predicateId = "launch-p1",
                        ),
                    ),
                    kind = TaskMilestoneKind.LAUNCH_APP,
                ),
            ),
        )

        val once = recipe.normalizePlan(original)
        val twice = recipe.normalizePlan(once)

        assertEquals(3, once.milestones.size)
        assertEquals(once, twice)
        assertEquals("recipe_search_input", once.milestones[1].id)
        assertEquals("recipe_search_submit", once.milestones[2].id)
    }

    @Test
    fun recipeUsesExactInputThenOneShotSubmit() {
        val recipe = TaskRecipeRegistry.select(
            GoalContext("在B站搜索老番茄"),
            TaskRecipeRegistry.BILIBILI_PACKAGE,
        )!!
        val plan = recipe.normalizePlan(basePlan())
        val inputMilestone = plan.milestones.first { it.id == "recipe_search_input" }
        val submitMilestone = plan.milestones.first { it.id == "recipe_search_submit" }
        val field = node(1, text = "", description = "搜索", viewId = "search_text", editable = true)

        val input = recipe.requiredAction(Observation("tv.danmaku.bili", listOf(field), imeVisible = true), inputMilestone)
        assertTrue(input is AgentAction.InputText)
        assertEquals("老番茄", (input as AgentAction.InputText).text)
        assertEquals("recipe_search_input-p1", input.predicateId)

        val exact = field.copy(text = "老番茄")
        val submit = recipe.requiredAction(Observation("tv.danmaku.bili", listOf(exact), imeVisible = true), submitMilestone)
        assertTrue(submit is AgentAction.SubmitInput)
        assertNull(recipe.requiredAction(Observation("tv.danmaku.bili", listOf(exact), imeVisible = false), submitMilestone))
    }

    @Test
    fun bilibiliRecipeRejectsUnrelatedHotSearchAndWrongToggle() {
        val recipe = TaskRecipeRegistry.select(
            GoalContext("打开B站给老番茄的最新视频点赞"),
            TaskRecipeRegistry.BILIBILI_PACKAGE,
        )!!
        val milestone = basePlan().milestones.single()
        val hot = node(1, text = "英国史", description = "热搜", viewId = "hot_search", clickable = true)
        val wrongToggle = node(2, text = "自动播放", description = "", viewId = "autoplay_switch", clickable = true, checked = false)
        val observation = Observation("tv.danmaku.bili", listOf(hot, wrongToggle))

        assertNotNull(recipe.rejectAction(AgentAction.ClickNode(1), observation, milestone))
        assertNotNull(recipe.rejectAction(AgentAction.EnsureToggle(2, true), observation, milestone))
    }

    @Test
    fun deterministicSearchPreludeCanAdvanceFromLaunchThroughSubmission() {
        val recipe = TaskRecipeRegistry.select(
            GoalContext("在B站搜索老番茄"),
            TaskRecipeRegistry.BILIBILI_PACKAGE,
        )!!
        val plan = recipe.normalizePlan(basePlan())
        val ledger = RunLedger(plan)
        val bindings = PredicateBindingStore()
        val launchObservation = Observation(TaskRecipeRegistry.BILIBILI_PACKAGE, emptyList())
        assertTrue(
            MilestoneEvaluator.evaluate(
                ledger.currentMilestone!!,
                plan,
                launchObservation,
                TaskRecipeRegistry.BILIBILI_PACKAGE,
                bindings,
            ).proven,
        )
        ledger.advance("foreground")

        val emptyField = node(1, text = "", description = "搜索", viewId = "search_text", editable = true)
        val beforeInput = Observation(TaskRecipeRegistry.BILIBILI_PACKAGE, listOf(emptyField), imeVisible = true)
        val inputAction = recipe.requiredAction(beforeInput, ledger.currentMilestone!!) as AgentAction.InputText
        val preparation = bindings.prepareActionBinding(ledger.currentMilestone!!, inputAction, beforeInput, "run-1")
        assertTrue(preparation.prepared)
        assertTrue(bindings.commitObservation(preparation, BindingOrigin.OBSERVATION_ONLY))
        val afterInput = Observation(
            TaskRecipeRegistry.BILIBILI_PACKAGE,
            listOf(emptyField.copy(text = "老番茄")),
            imeVisible = true,
        )
        assertTrue(
            MilestoneEvaluator.evaluate(
                ledger.currentMilestone!!,
                plan,
                afterInput,
                TaskRecipeRegistry.BILIBILI_PACKAGE,
                bindings,
                runId = "run-1",
            ).proven,
        )
        ledger.advance("exact query")

        val submitted = afterInput.copy(imeVisible = false)
        assertTrue(
            MilestoneEvaluator.evaluate(
                ledger.currentMilestone!!,
                plan,
                submitted,
                TaskRecipeRegistry.BILIBILI_PACKAGE,
                bindings,
                runId = "run-1",
            ).proven,
        )
    }

    private fun basePlan() = TaskPlan(
        summary = "search",
        targetAppHint = "Bilibili",
        goal = GoalContext("在B站搜索老番茄"),
        milestones = listOf(
            TaskMilestone(
                id = "launch",
                objective = "launch",
                successPredicates = listOf(
                    UiPredicate(
                        UiPredicateKind.PACKAGE_FOREGROUND,
                        targetPackage = TaskRecipeRegistry.BILIBILI_PACKAGE,
                        description = "foreground",
                        predicateId = "launch-p1",
                    ),
                ),
                kind = TaskMilestoneKind.LAUNCH_APP,
            ),
        ),
    )

    private fun node(
        id: Int,
        text: String,
        description: String,
        viewId: String,
        editable: Boolean = false,
        clickable: Boolean = false,
        checked: Boolean? = null,
    ) = UiNodeSnapshot(
        id = id,
        text = text,
        description = description,
        className = if (editable) "android.widget.EditText" else "android.widget.TextView",
        clickable = clickable,
        editable = editable,
        bounds = "0,0,100,40",
        viewId = viewId,
        packageName = TaskRecipeRegistry.BILIBILI_PACKAGE,
        windowId = 1,
        treePath = listOf(0, id),
        checked = checked,
    )
}
