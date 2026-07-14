package com.androidagent.app.agent

import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class ElementMatchPolicyTest {
    private val target = UiNodeSnapshot(4, "搜索", "", "ImageView", true, false, "10,10,50,50", viewId = "bili:id/search")

    @Test
    fun stableViewIdSurvivesTreeAndBoundsMovement() {
        val score = ElementMatchPolicy.score(target, "bili:id/search", "搜索", "", "ImageView", "12,12,52,52", false, true)
        assertTrue(score >= ElementMatchPolicy.minimumScore(target))
    }

    @Test
    fun unrelatedKeyboardDigitDoesNotMatchTarget() {
        val score = ElementMatchPolicy.score(target, "keyboard:id/key5", "5", "", "TextView", "10,10,50,50", false, true)
        assertTrue(score < ElementMatchPolicy.minimumScore(target))
    }

    @Test
    fun semanticEnglishHintUsesNotificationTextAndSwitchAsStructureOnly() {
        val node = UiNodeSnapshot(1, "Notifications", "", "android.widget.Switch", true, false, "0,0,100,30")
        assertEquals(TargetHintResult.MATCH, TargetHintMatcher.match("notification settings toggle", node))
        assertEquals(TargetHintResult.NO_MATCH, TargetHintMatcher.match("on", node))
    }

    @Test
    fun semanticChineseHintStripsGenericControlWords() {
        val node = UiNodeSnapshot(1, "通知", "", "android.widget.Switch", true, false, "0,0,100,30")
        assertEquals(TargetHintResult.MATCH, TargetHintMatcher.match("通知设置开关", node))
        val generic = UiNodeSnapshot(2, "", "", "android.widget.Switch", true, false, "0,40,100,70")
        assertEquals(TargetHintResult.NO_MATCH, TargetHintMatcher.match("开关", generic))
        assertEquals(TargetHintResult.NO_MATCH, TargetHintMatcher.match("自动领取金币", generic))
    }

    @Test
    fun duplicateBestSemanticTargetsAreAmbiguous() {
        val nodes = listOf(
            UiNodeSnapshot(1, "Notifications", "", "android.widget.Switch", true, false, "0,0,100,30"),
            UiNodeSnapshot(2, "Notifications", "", "android.widget.Switch", true, false, "0,40,100,70"),
        )
        assertEquals(TargetHintResult.AMBIGUOUS, TargetHintMatcher.match("notification settings toggle", nodes))
    }
}
