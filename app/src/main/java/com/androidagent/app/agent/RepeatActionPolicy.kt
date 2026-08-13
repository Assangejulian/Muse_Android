package com.androidagent.app.agent

/**
 * Generic anti-spin for identical consecutive turns. Scrolling may repeat;
 * finding or clicking the same target again must not.
 */
object RepeatActionPolicy {
    fun allowsRepeat(action: AgentAction): Boolean = when (action) {
        is AgentAction.Swipe, is AgentAction.ScrollUntil, is AgentAction.Wait, is AgentAction.WaitUntil -> true
        else -> false
    }

    fun fingerprint(action: AgentAction): String = when (action) {
        is AgentAction.ClickNode -> "click_node:${action.nodeId}:${action.selector}"
        is AgentAction.ClickText -> "click_text:${action.text.trim().lowercase()}"
        is AgentAction.FindNodes -> "find_nodes:${action.query.signature()}"
        is AgentAction.ReadNode -> "read_node:${action.nodeId}:${action.selector}"
        is AgentAction.EnsureToggle -> "ensure_toggle:${action.nodeId}:${action.desired}"
        is AgentAction.InputText -> "input_text:${action.nodeId}:${action.mode}:${action.text.length}"
        is AgentAction.SubmitInput -> "submit_input:${action.nodeId}"
        is AgentAction.LaunchApp -> "launch_app:${action.packageName}"
        is AgentAction.TapPoint -> "tap_point:${action.x / 24}:${action.y / 24}"
        is AgentAction.Swipe -> "swipe:${action.direction}"
        is AgentAction.ScrollUntil -> "scroll_until:${action.direction}:${action.query.signature()}"
        is AgentAction.WaitUntil -> "wait_until:${action.query.signature()}"
        is AgentAction.Terminal -> "terminal:${action.command.length}"
        is AgentAction.Wait -> "wait:${action.milliseconds}"
        is AgentAction.BindPredicate -> "bind:${action.predicateId}"
        AgentAction.Back -> "back"
        AgentAction.Home -> "home"
        is AgentAction.Finish -> "finish"
        is AgentAction.Fail -> "fail"
    }

    fun shouldBlock(previousFingerprint: String?, action: AgentAction): Boolean {
        if (previousFingerprint.isNullOrBlank() || allowsRepeat(action)) return false
        return fingerprint(action) == previousFingerprint
    }

    fun rejection(action: AgentAction): String =
        "REPEAT: ${fingerprint(action)} just ran. Take a different live action."
}
