package com.androidagent.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QueryLoopPolicyTest {
    @Test
    fun allowsTwoQueriesThenBlocksTheThird() {
        val find = AgentAction.FindNodes(NodeQuery(text = "ok"))
        val click = AgentAction.ClickNode(3)
        assertFalse(QueryLoopPolicy.shouldBlock(0, find))
        assertFalse(QueryLoopPolicy.shouldBlock(1, find))
        assertTrue(QueryLoopPolicy.shouldBlock(2, find))
        assertTrue(QueryLoopPolicy.shouldBlock(0, find, lastQueryFound = true))
        assertFalse(QueryLoopPolicy.shouldBlock(2, click))
        assertEquals(1, QueryLoopPolicy.nextCount(0, find))
        assertEquals(0, QueryLoopPolicy.nextCount(2, click))
    }
}
