package com.androidagent.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behavioral search/app recipes were removed in favor of model-first planning.
 * These tests lock that policy and keep catalog-only package resolution.
 */
class TaskRecipesTest {
    @Test
    fun registryNeverSelectsHardcodedWorkflowRecipes() {
        val goals = listOf(
            "打开B站给热搜的第一的视频下面的第一个评论点赞",
            "打开B站给老番茄的最新视频点赞",
            "在B站搜索老番茄并点赞",
            "搜索猫咪视频，然后播放第一个结果",
            "open settings and enable wifi",
        )
        goals.forEach { goal ->
            assertNull(
                "recipe must stay disabled for: $goal",
                TaskRecipeRegistry.select(GoalContext(goal), "tv.danmaku.bili"),
            )
            assertNull(TaskRecipeRegistry.select(GoalContext(goal), null))
        }
    }

    @Test
    fun resolveTargetPackageUsesInstalledCatalogOnly() {
        val apps = listOf(
            "哔哩哔哩" to "tv.danmaku.bili",
            "设置" to "com.android.settings",
        )

        assertEquals("tv.danmaku.bili", resolveTargetPackage("tv.danmaku.bili", "打开它", apps))
        assertEquals("tv.danmaku.bili", resolveTargetPackage("", "打开哔哩哔哩", apps))
        assertEquals("com.android.settings", resolveTargetPackage("", "打开设置", apps))
        // Nickname that does not appear in the label is left for Manager + catalog, not hard aliases.
        assertNull(resolveTargetPackage("", "打开B站", apps))
    }

    @Test
    fun toolGuardStillForcesLaunchOnlyForLaunchMilestones() {
        val plan = TaskPlan(
            summary = "launch",
            targetAppHint = "example",
            goal = GoalContext("open example"),
            milestones = listOf(
                TaskMilestone(
                    id = "launch",
                    objective = "launch",
                    successPredicates = listOf(
                        UiPredicate(
                            UiPredicateKind.PACKAGE_FOREGROUND,
                            targetPackage = "example.app",
                            description = "foreground",
                            predicateId = "launch-p1",
                        ),
                    ),
                    kind = TaskMilestoneKind.LAUNCH_APP,
                ),
            ),
        )
        val guard = ToolGuard(plan, "example.app")
        // Model-first: local workflow never hijacks launch; Actor decides.
        assertNull(guard.requiredWorkflowAction(Observation("com.android.launcher3", emptyList()), plan.milestones.single()))
        assertNull(guard.requiredWorkflowAction(Observation("example.app", emptyList()), plan.milestones.single()))
    }
}
