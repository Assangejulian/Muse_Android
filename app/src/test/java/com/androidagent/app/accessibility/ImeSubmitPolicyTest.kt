package com.androidagent.app.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImeSubmitPolicyTest {
    @Test
    fun acceptsGenericImeActionLabel() {
        val score = ImeSubmitPolicy.score("com.example.ime:id/action_key", "done", "", true, true, true)
        assertTrue(score >= ImeSubmitPolicy.MINIMUM_SCORE)
    }

    @Test
    fun acceptsSemanticImeActionViewIdWithoutText() {
        val score = ImeSubmitPolicy.score("com.example.ime:id/ime_action_done", "", "", true, true, true)
        assertTrue(score >= ImeSubmitPolicy.MINIMUM_SCORE)
    }

    @Test
    fun rejectsGenericEnterKeysAndSuggestions() {
        assertEquals(0, ImeSubmitPolicy.score("com.example.keyboard:id/key_enter", "", "", true, true, true))
        assertEquals(0, ImeSubmitPolicy.score("com.example.keyboard:id/candidate", "done", "", true, true, true))
    }

    @Test
    fun ocrPartialMatchDoesNotShrinkToOneCharacter() {
        assertEquals(false, ImeSubmitPolicy.isSafeOcrPartialMatch("long target", "5"))
        assertEquals(true, ImeSubmitPolicy.isSafeOcrPartialMatch("target", "target result"))
    }
}
