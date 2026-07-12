package com.androidagent.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BilibiliTraceRegressionTest {
    private val plan = TaskPlanParser.fallback(
        "打开B站给老番茄的最新视频点赞",
        "哔哩哔哩",
        "老番茄",
    )
    private val guard = ToolGuard(plan)
    private val selectEntity = plan.milestones.first { it.kind == TaskMilestoneKind.SELECT_ENTITY }

    @Test
    fun targetAppLaunchIsHarnessOwned() {
        val launch = plan.milestones.first { it.kind == TaskMilestoneKind.LAUNCH_APP }
        val packageBoundGuard = ToolGuard(plan, "tv.danmaku.bili")
        val muse = Observation("com.androidagent.app", emptyList())

        assertEquals(AgentAction.LaunchApp("tv.danmaku.bili"), packageBoundGuard.requiredWorkflowAction(muse, launch))
    }

    @Test
    fun exactQueryWithSogouVisibleForcesSubmitInsteadOfSuggestionClick() {
        val screen = searchScreen("老番茄", imeVisible = true)

        assertEquals(AgentAction.SubmitInput(1), guard.requiredWorkflowAction(screen, selectEntity))
        assertEquals(
            AgentAction.SubmitInput(1),
            guard.normalizeAndValidate(AgentAction.ClickText("英国史"), screen, selectEntity).action,
        )
    }

    @Test
    fun successfulSubmitIsFollowedByImeDismissInsteadOfSecondSubmit() {
        val screen = searchScreen("老番茄", imeVisible = true)
        val submit = guard.requiredWorkflowAction(screen, selectEntity)!!

        guard.recordDispatch(submit)

        assertEquals(AgentAction.Back, guard.requiredWorkflowAction(screen, selectEntity))
    }

    @Test
    fun appendedFiveIsReplacedWithCanonicalQuery() {
        val screen = searchScreen("老番茄5", imeVisible = true)

        assertEquals(AgentAction.InputText("老番茄", 1), guard.requiredWorkflowAction(screen, selectEntity))
    }

    @Test
    fun delayedSearchFieldCannotExposeHotTermsToTheActor() {
        val enterQuery = plan.milestones.first { it.kind == TaskMilestoneKind.ENTER_QUERY }
        val loadingSearch = Observation(
            "tv.danmaku.bili",
            listOf(
                UiNodeSnapshot(1, "", "搜索", "Button", true, false, "500,0,600,80", packageName = "tv.danmaku.bili", viewId = "search_icon"),
                UiNodeSnapshot(2, "英国史", "", "TextView", true, false, "0,100,600,180", packageName = "tv.danmaku.bili", viewId = "search_suggest_item"),
            ),
        )

        assertEquals(AgentAction.ClickNode(1), guard.normalizeAndValidate(AgentAction.ClickNode(1), loadingSearch, enterQuery).action)
        assertNull(guard.normalizeAndValidate(AgentAction.ClickNode(2), loadingSearch, enterQuery).action)
        assertNull(guard.normalizeAndValidate(AgentAction.ClickText("英国史"), loadingSearch, enterQuery).action)
    }

    @Test
    fun unrelatedBilibiliSuggestionsAndVisualPointsAreRejected() {
        val screen = searchScreen("老番茄", imeVisible = false)

        assertNull(guard.normalizeAndValidate(AgentAction.ClickText("英国史"), screen, selectEntity).action)
        assertNull(guard.normalizeAndValidate(AgentAction.ClickNode(2), screen, selectEntity).action)
        assertNull(guard.normalizeAndValidate(AgentAction.TapPoint(500, 400), screen, selectEntity).action)
        assertNull(guard.normalizeAndValidate(AgentAction.ClickText("老番茄5"), screen, selectEntity).action)
        assertNull(guard.normalizeAndValidate(AgentAction.ClickText("老番茄·5小时前更新"), screen, selectEntity).action)
    }

    @Test
    fun wrongProfileCannotSatisfyCreatorOrContentHardGates() {
        val wrongProfile = Observation(
            "tv.danmaku.bili",
            listOf(UiNodeSnapshot(8, "洛克王国世界", "", "TextView", false, false, "0,0,500,80", packageName = "tv.danmaku.bili")),
        )
        val openContent = plan.milestones.first { it.kind == TaskMilestoneKind.OPEN_CONTENT }

        assertFalse(MilestoneEvaluator.evaluateHardPredicates(selectEntity, plan, wrongProfile, "tv.danmaku.bili").proven)
        assertFalse(MilestoneEvaluator.evaluateHardPredicates(openContent, plan, wrongProfile, "tv.danmaku.bili").proven)
    }

    @Test
    fun searchResultRowDoesNotMasqueradeAsAnOpenedProfile() {
        val resultRow = Observation(
            "tv.danmaku.bili",
            listOf(
                UiNodeSnapshot(1, "老番茄", "", "TextView", true, false, "0,100,400,170", treePath = "w0/2/0/0", packageName = "tv.danmaku.bili"),
                UiNodeSnapshot(2, "1200万粉丝", "", "TextView", false, false, "0,175,300,220", treePath = "w0/2/0/1", packageName = "tv.danmaku.bili"),
                UiNodeSnapshot(3, "投稿 500", "", "TextView", false, false, "310,175,500,220", treePath = "w0/2/0/2", packageName = "tv.danmaku.bili"),
            ),
        )

        assertFalse(MilestoneEvaluator.evaluateHardPredicates(selectEntity, plan, resultRow, "tv.danmaku.bili").proven)
    }

    @Test
    fun creatorProfileVideoListDoesNotMasqueradeAsOpenedVideo() {
        val openContent = plan.milestones.first { it.kind == TaskMilestoneKind.OPEN_CONTENT }
        val profile = Observation(
            "tv.danmaku.bili",
            listOf(
                UiNodeSnapshot(1, "老番茄", "", "TextView", false, false, "0,0,400,80", treePath = "w0/1/0/0", packageName = "tv.danmaku.bili"),
                UiNodeSnapshot(2, "关注", "", "Button", true, false, "410,0,520,80", treePath = "w0/1/0/1", packageName = "tv.danmaku.bili"),
                UiNodeSnapshot(3, "动态", "", "Tab", true, false, "0,100,160,160", treePath = "w0/2", packageName = "tv.danmaku.bili", viewId = "space_tab"),
                UiNodeSnapshot(4, "最新视频 播放 100万", "", "TextView", true, false, "0,200,600,400", treePath = "w0/3", packageName = "tv.danmaku.bili", viewId = "video_title"),
            ),
        )

        assertFalse(MilestoneEvaluator.evaluateHardPredicates(openContent, plan, profile, "tv.danmaku.bili").proven)
    }

    @Test
    fun openContentOnlyAcceptsVideoBoundNodesOnTheTargetProfile() {
        val openContent = plan.milestones.first { it.kind == TaskMilestoneKind.OPEN_CONTENT }
        val profile = Observation(
            "tv.danmaku.bili",
            listOf(
                UiNodeSnapshot(1, "老番茄", "", "TextView", false, false, "0,0,400,80", treePath = "w0/1/0/0", packageName = "tv.danmaku.bili"),
                UiNodeSnapshot(2, "关注", "", "Button", true, false, "410,0,520,80", treePath = "w0/1/0/1", packageName = "tv.danmaku.bili"),
                UiNodeSnapshot(3, "动态", "", "Tab", true, false, "0,100,160,160", treePath = "w0/2", packageName = "tv.danmaku.bili", viewId = "space_tab"),
                UiNodeSnapshot(4, "1200万粉丝", "", "Button", true, false, "170,100,360,160", treePath = "w0/2/1", packageName = "tv.danmaku.bili"),
                UiNodeSnapshot(5, "新的旅程·1小时前更新", "", "TextView", true, false, "0,200,600,400", treePath = "w0/3", packageName = "tv.danmaku.bili", viewId = "video_title"),
                UiNodeSnapshot(6, "旧的旅程·5小时前更新", "", "TextView", true, false, "0,600,600,800", treePath = "w0/4", packageName = "tv.danmaku.bili", viewId = "video_title"),
                UiNodeSnapshot(7, "置顶·很久以前的视频·2026-01-01", "", "TextView", true, false, "0,170,600,195", treePath = "w0/5", packageName = "tv.danmaku.bili", viewId = "video_title"),
            ),
        )

        assertEquals(AgentAction.ClickNode(5), guard.normalizeAndValidate(AgentAction.ClickNode(5), profile, openContent).action)
        assertNull(guard.normalizeAndValidate(AgentAction.ClickNode(6), profile, openContent).action)
        assertNull(guard.normalizeAndValidate(AgentAction.ClickNode(7), profile, openContent).action)
        assertNull(guard.normalizeAndValidate(AgentAction.ClickNode(4), profile, openContent).action)
    }

    @Test
    fun finalLikeIsBlockedUntilTargetCreatorAndVideoControlsAreProven() {
        val finalMilestone = plan.milestones.last()
        val wrongVideo = Observation(
            "tv.danmaku.bili",
            listOf(
                UiNodeSnapshot(1, "洛克王国世界", "", "TextView", false, false, "0,0,400,80", treePath = "w0/1/0/0", packageName = "tv.danmaku.bili"),
                UiNodeSnapshot(2, "关注", "", "Button", true, false, "410,0,520,80", treePath = "w0/1/0/1", packageName = "tv.danmaku.bili"),
                UiNodeSnapshot(3, "点赞 1.8万", "", "Button", true, false, "0,500,150,580", treePath = "w0/2", packageName = "tv.danmaku.bili"),
                UiNodeSnapshot(4, "评论 1024", "", "Button", true, false, "160,500,310,580", treePath = "w0/3", packageName = "tv.danmaku.bili"),
            ),
        )

        val result = guard.normalizeAndValidate(AgentAction.ClickNode(3), wrongVideo, finalMilestone)

        assertNull(result.action)
        assertTrue(result.rejection.orEmpty().contains("target-content proof"))
    }

    @Test
    fun finalEnsureToggleMustStillTargetTheSemanticLikeControl() {
        val finalMilestone = plan.milestones.last()
        val targetVideo = Observation(
            "tv.danmaku.bili",
            listOf(
                UiNodeSnapshot(1, "老番茄", "", "TextView", false, false, "0,0,400,80", treePath = "w0/1/0/0", packageName = "tv.danmaku.bili"),
                UiNodeSnapshot(2, "关注", "", "Button", true, false, "410,0,520,80", treePath = "w0/1/0/1", packageName = "tv.danmaku.bili"),
                UiNodeSnapshot(3, "点赞 1.8万", "", "Button", true, false, "0,500,150,580", treePath = "w0/2", packageName = "tv.danmaku.bili"),
                UiNodeSnapshot(4, "评论 1024", "", "Button", true, false, "160,500,310,580", treePath = "w0/3", packageName = "tv.danmaku.bili"),
            ),
        )

        assertEquals(AgentAction.EnsureToggle(3, true), guard.normalizeAndValidate(AgentAction.EnsureToggle(3, true), targetVideo, finalMilestone).action)
        assertNull(guard.normalizeAndValidate(AgentAction.EnsureToggle(4, true), targetVideo, finalMilestone).action)
    }

    @Test
    fun wrongCreatorVideoForcesGuardOwnedBackAndBlocksHomeReset() {
        val openContent = plan.milestones.first { it.kind == TaskMilestoneKind.OPEN_CONTENT }
        val wrongVideo = Observation(
            "tv.danmaku.bili",
            listOf(
                UiNodeSnapshot(1, "洛克王国世界", "", "TextView", false, false, "0,0,400,80", treePath = "w0/1/0/0", packageName = "tv.danmaku.bili"),
                UiNodeSnapshot(2, "点赞", "", "Button", true, false, "0,500,150,580", treePath = "w0/2", packageName = "tv.danmaku.bili"),
                UiNodeSnapshot(3, "评论", "", "Button", true, false, "160,500,310,580", treePath = "w0/3", packageName = "tv.danmaku.bili"),
            ),
        )
        val creatorProfile = Observation(
            "tv.danmaku.bili",
            listOf(UiNodeSnapshot(9, "老番茄", "", "TextView", false, false, "0,0,400,80", packageName = "tv.danmaku.bili")),
        )

        assertEquals(AgentAction.Back, guard.requiredWorkflowAction(wrongVideo, openContent))
        assertEquals(AgentAction.Back, guard.normalizeAndValidate(AgentAction.LaunchApp("tv.danmaku.bili"), wrongVideo, openContent).action)
        assertNull(guard.normalizeAndValidate(AgentAction.LaunchApp("tv.danmaku.bili"), creatorProfile, openContent).action)
        assertNull(guard.normalizeAndValidate(AgentAction.ClickText("首页"), creatorProfile, openContent).action)
    }

    @Test
    fun nonClickableActionLabelsStillIdentifyWrongVideoForRecovery() {
        val openContent = plan.milestones.first { it.kind == TaskMilestoneKind.OPEN_CONTENT }
        val wrongVideo = Observation(
            "tv.danmaku.bili",
            listOf(
                UiNodeSnapshot(1, "洛克王国世界", "", "TextView", false, false, "0,0,400,80", treePath = "w0/1", packageName = "tv.danmaku.bili"),
                UiNodeSnapshot(2, "", "", "Button", true, false, "0,500,150,580", treePath = "w0/2", packageName = "tv.danmaku.bili"),
                UiNodeSnapshot(3, "点赞", "", "TextView", false, false, "0,500,150,580", treePath = "w0/2/0", packageName = "tv.danmaku.bili"),
                UiNodeSnapshot(4, "", "", "Button", true, false, "160,500,310,580", treePath = "w0/3", packageName = "tv.danmaku.bili"),
                UiNodeSnapshot(5, "评论", "", "TextView", false, false, "160,500,310,580", treePath = "w0/3/0", packageName = "tv.danmaku.bili"),
            ),
        )

        assertEquals(AgentAction.Back, guard.requiredWorkflowAction(wrongVideo, openContent))
    }

    private fun searchScreen(query: String, imeVisible: Boolean): Observation = Observation(
        "tv.danmaku.bili",
        listOf(
            UiNodeSnapshot(1, query, "", "EditText", false, true, "0,0,600,80", treePath = "w0/0", focused = imeVisible, packageName = "tv.danmaku.bili", viewId = "search_text"),
            UiNodeSnapshot(2, "英国史", "", "TextView", true, false, "0,100,600,180", treePath = "w0/1", packageName = "tv.danmaku.bili"),
            UiNodeSnapshot(3, "洛克王国世界·5小时前更新", "", "TextView", true, false, "0,190,600,270", treePath = "w0/2", packageName = "tv.danmaku.bili"),
        ),
        imeVisible,
    )
}
