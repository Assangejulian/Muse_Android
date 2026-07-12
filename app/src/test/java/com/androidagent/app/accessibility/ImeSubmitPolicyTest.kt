package com.androidagent.app.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImeSubmitPolicyTest {
    @Test
    fun acceptsLocalizedSubmitLabels() {
        val score = ImeSubmitPolicy.score(
            viewId = "com.sohu.inputmethod.sogou:id/search_key",
            text = "搜索",
            description = "",
            clickable = true,
            enabled = true,
            visible = true,
        )

        assertTrue(score >= ImeSubmitPolicy.MINIMUM_SCORE)
    }

    @Test
    fun acceptsSemanticImeActionViewIdWithoutText() {
        val score = ImeSubmitPolicy.score(
            viewId = "com.google.android.inputmethod.latin:id/ime_action_search",
            text = "",
            description = "",
            clickable = true,
            enabled = true,
            visible = true,
        )

        assertTrue(score >= ImeSubmitPolicy.MINIMUM_SCORE)
    }

    @Test
    fun rejectsGenericEnterOrNewlineKeys() {
        assertEquals(
            0,
            ImeSubmitPolicy.score(
                viewId = "com.example.keyboard:id/key_enter",
                text = "换行",
                description = "",
                clickable = true,
                enabled = true,
                visible = true,
            ),
        )
    }

    @Test
    fun rejectsNumberKeyEvenWhenItsViewIdLooksActionable() {
        val score = ImeSubmitPolicy.score(
            viewId = "com.example.keyboard:id/enter_candidate",
            text = "5",
            description = "5",
            clickable = true,
            enabled = true,
            visible = true,
        )

        assertEquals(0, score)
    }

    @Test
    fun rejectsSuggestionsAndHiddenNodes() {
        assertEquals(
            0,
            ImeSubmitPolicy.score(
                viewId = "com.example.keyboard:id/candidate_search",
                text = "搜索",
                description = "",
                clickable = true,
                enabled = true,
                visible = true,
            ),
        )
        assertEquals(
            0,
            ImeSubmitPolicy.score(
                viewId = "com.example.keyboard:id/key_enter",
                text = "",
                description = "",
                clickable = true,
                enabled = true,
                visible = false,
            ),
        )
    }

    @Test
    fun ocrPartialMatchNeverShrinksLongTargetToKeyboardDigit() {
        assertEquals(false, ImeSubmitPolicy.isSafeOcrPartialMatch("洛克王国世界·5小时前更新", "5"))
        assertEquals(true, ImeSubmitPolicy.isSafeOcrPartialMatch("老番茄", "UP主老番茄"))
        assertEquals(false, ImeSubmitPolicy.isSafeOcrPartialMatch("5", "5"))
    }

}
