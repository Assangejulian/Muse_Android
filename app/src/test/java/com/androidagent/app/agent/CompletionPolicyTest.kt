package com.androidagent.app.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompletionPolicyTest {
    @Test
    fun appLaunchIsNotEnoughForLikeTask() {
        assertFalse(CompletionPolicy.hasMinimumEvidence("打开B站给老番茄最新视频点赞", listOf("LaunchApp(packageName=bili)")))
    }

    @Test
    fun acceptsSearchOpenAndLikeSequence() {
        assertTrue(CompletionPolicy.hasMinimumEvidence(
            "打开B站给老番茄最新视频点赞",
            listOf("LaunchApp(packageName=bili)", "InputText(text=老番茄)", "ClickNode(nodeId=1)", "ClickNode(nodeId=2)"),
        ))
    }
}
