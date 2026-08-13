package com.androidagent.app.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RepeatActionPolicyTest {
    @Test
    fun blocksTheSameClickOrFindTwiceAndAllowsAnotherSwipe() {
        val click = AgentAction.ClickNode(4)
        val find = AgentAction.FindNodes(NodeQuery(text = "ok"))
        assertTrue(RepeatActionPolicy.shouldBlock(RepeatActionPolicy.fingerprint(click), click))
        assertTrue(RepeatActionPolicy.shouldBlock(RepeatActionPolicy.fingerprint(find), find))
        assertFalse(RepeatActionPolicy.shouldBlock(RepeatActionPolicy.fingerprint(AgentAction.Swipe("up")), AgentAction.Swipe("up")))
        assertFalse(RepeatActionPolicy.shouldBlock(RepeatActionPolicy.fingerprint(click), AgentAction.ClickNode(5)))
    }
}
