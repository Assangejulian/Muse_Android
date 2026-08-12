package com.androidagent.app.agent

/** Human-readable action labels for Actor history. Live UI text is already on screen. */
internal object ActorActionLabel {
    fun describe(action: AgentAction, target: UiNodeSnapshot? = null): String = when (action) {
        is AgentAction.LaunchApp -> "launch_app(${action.packageName})"
        is AgentAction.ClickText -> "click_text(${action.text.take(40)})"
        is AgentAction.ClickNode -> {
            val label = target?.let { it.text.ifBlank { it.description } }?.take(40)
            if (label.isNullOrBlank()) "click_node(#${action.nodeId})" else "click_node(#${action.nodeId}, $label)"
        }
        is AgentAction.TapPoint -> "tap_point(${action.x},${action.y})"
        is AgentAction.Swipe -> "swipe(${action.direction})"
        is AgentAction.InputText -> "input_text(#${action.nodeId ?: 0}, ${action.text.length} chars)"
        is AgentAction.SubmitInput -> "submit_input(#${action.nodeId ?: 0})"
        is AgentAction.EnsureToggle -> "ensure_toggle(#${action.nodeId}, ${action.desired})"
        is AgentAction.BindPredicate -> "bind_predicate(${action.predicateId})"
        is AgentAction.Terminal -> "terminal(${action.command.length} chars)"
        is AgentAction.Wait -> "wait(${action.milliseconds}ms)"
        is AgentAction.Finish -> "finish"
        is AgentAction.Fail -> "fail"
        AgentAction.Back -> "back"
        AgentAction.Home -> "home"
    }
}
