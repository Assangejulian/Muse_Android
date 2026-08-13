package com.androidagent.app.agent

/**
 * Generic anti-spin: find/read do not change the screen. After a few in a row
 * the Actor must act on ids it already has. No task vocabulary.
 */
object QueryLoopPolicy {
    const val MAX_CONSECUTIVE = 2

    fun isQuery(action: AgentAction): Boolean =
        action is AgentAction.FindNodes || action is AgentAction.ReadNode

    fun shouldBlock(consecutiveQueries: Int, action: AgentAction): Boolean =
        isQuery(action) && consecutiveQueries >= MAX_CONSECUTIVE

    fun nextCount(consecutiveQueries: Int, action: AgentAction): Int =
        if (isQuery(action)) consecutiveQueries + 1 else 0

    fun rejection(consecutiveQueries: Int): String =
        "QUERY_LOOP: $consecutiveQueries consecutive find_nodes/read_node turns. " +
            "Act now with click_node, click_text, scroll_until, swipe, input_text, back, or wait_until " +
            "using node ids already returned. Do not query again."
}
