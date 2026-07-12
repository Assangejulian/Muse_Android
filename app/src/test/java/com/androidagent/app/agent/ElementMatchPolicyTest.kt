package com.androidagent.app.agent

import org.junit.Assert.assertTrue
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
}
