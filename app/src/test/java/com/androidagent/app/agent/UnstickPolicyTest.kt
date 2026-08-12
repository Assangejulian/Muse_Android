package com.androidagent.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnstickPolicyTest {
    @Test
    fun extractsLocateTokensAndIgnoresGenericGlue() {
        val tokens = UnstickPolicy.tokens("打开B站给热搜的第一个视频的第一个评论点赞")
        assertTrue(tokens.contains("热搜"))
        assertTrue(tokens.contains("评论") || tokens.contains("点赞"))
        assertFalse(tokens.contains("打开"))
        assertFalse(tokens.contains("第一个"))
    }

    @Test
    fun treatsAOneCharacterNeighborAsANearMiss() {
        assertTrue(UnstickPolicy.isNearMiss("热门", "热搜"))
        assertTrue(UnstickPolicy.isNearMiss("微店", "微信"))
        assertFalse(UnstickPolicy.isNearMiss("热搜", "热搜"))
        assertFalse(UnstickPolicy.isNearMiss("评论", "热搜"))
    }

    @Test
    fun missingTokensClearAfterTheyHaveBeenSeen() {
        val goal = "打开设置打开WLAN"
        val settingsHome = Observation(
            "com.android.settings",
            listOf(UiNodeSnapshot(1, "WLAN", "", "TextView", true, false, "0,0,100,40")),
        )
        val seen = UnstickPolicy.seenOn(settingsHome, goal)
        assertTrue(seen.contains("WLAN") || seen.any { it.contains("WLAN", true) || it.contains("设置") })
        val later = Observation("com.android.settings", listOf(UiNodeSnapshot(1, "已连接", "", "TextView", false, false, "0,0,100,40")))
        val missing = UnstickPolicy.missingTokens(goal, later, seen)
        assertFalse(missing.contains("WLAN"))
    }

    @Test
    fun findsSearchFieldsWithoutAppSpecificIds() {
        val screen = Observation(
            "any.app",
            listOf(
                UiNodeSnapshot(1, "热门", "", "Button", true, false, "0,0,80,40"),
                UiNodeSnapshot(2, "苏醒游戏解说", "搜索", "EditText", true, true, "80,0,300,40", viewId = "any:id/search_bar"),
            ),
        )
        val search = UnstickPolicy.searchTargets(screen)
        assertEquals(1, search.size)
        assertEquals(2, search.single().id)
        assertEquals("热搜", UnstickPolicy.nearMissToken("热门", listOf("热搜")))
    }
}
