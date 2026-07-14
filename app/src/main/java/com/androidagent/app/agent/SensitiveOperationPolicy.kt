package com.androidagent.app.agent

/**
 * One local policy for high-risk operations.  The policy is deliberately
 * conservative: a match blocks the operation before any model request or
 * mutating accessibility action is allowed to proceed.
 */
object SensitiveOperationPolicy {
    private val terms = listOf(
        // English operation names.
        "payment", "pay", "checkout", "purchase", "recharge", "transfer", "bank card",
        "verification code", "one-time password", "otp", "password", "change password",
        "reset password", "account security", "identity verification", "grant permission",
        "allow permission", "install app", "uninstall app", "system settings",
        // Common Chinese labels are escaped so source encoding cannot corrupt the policy.
        "\u652f\u4ed8", "\u4ed8\u6b3e", "\u8d2d\u4e70", "\u7ed3\u7b97", "\u6536\u94f6\u53f0",
        "\u5145\u503c", "\u8f6c\u8d26", "\u94f6\u884c\u5361", "\u9a8c\u8bc1\u7801", "\u52a8\u6001\u53e3\u4ee4",
        "\u4e00\u6b21\u6027\u5bc6\u7801", "\u767b\u5f55\u5bc6\u7801", "\u4fee\u6539\u5bc6\u7801", "\u91cd\u7f6e\u5bc6\u7801",
        "\u8d26\u53f7\u5b89\u5168", "\u5b9e\u540d\u8ba4\u8bc1", "\u8eab\u4efd\u8bc1", "\u6388\u4e88\u6743\u9650",
        "\u6388\u6743", "\u5b89\u88c5\u5e94\u7528", "\u5378\u8f7d\u5e94\u7528", "\u7cfb\u7edf\u8bbe\u7f6e",
    )

    private val otpContext = Regex(
        "(?i)(?:code|otp|verification|one[- ]time|\u9a8c\u8bc1\u7801|\u52a8\u6001\u53e3\u4ee4)[^0-9]{0,16}\\d{4,8}",
    )

    data class Match(val term: String, val source: String)

    fun matchGoal(goal: String): Match? = matchText(goal, "goal")

    fun matchText(text: String, source: String = "text"): Match? {
        if (text.isBlank()) return null
        val lower = text.lowercase()
        terms.firstOrNull { lower.contains(it.lowercase()) }?.let { return Match(it, source) }
        if (otpContext.containsMatchIn(text)) return Match("otp", source)
        return null
    }

    fun matchObservation(observation: Observation): Match? {
        if (observation.nodes.any { it.visible && it.password }) {
            return Match("password-field", "observation")
        }
        val visible = observation.nodes.asSequence()
            .filter { it.visible && !it.isInputMethod && !it.password }
            .flatMap { sequenceOf(it.text, it.description) }
            .filter(String::isNotBlank)
            .joinToString(" ")
        return matchText(visible, "observation")
    }

    fun matchAction(action: AgentAction): Match? = when (action) {
        is AgentAction.InputText -> matchText(action.text, "input")
        is AgentAction.ClickText -> matchText(action.text, "click")
        is AgentAction.ClickNode -> selectorMatch(action.selector, "click_node")
        is AgentAction.EnsureToggle -> selectorMatch(action.selector, "toggle")
        is AgentAction.SubmitInput -> selectorMatch(action.target, "submit")
        is AgentAction.Finish, is AgentAction.Fail -> null
        else -> null
    }

    fun isSafeRecoveryAction(action: AgentAction): Boolean = when (action) {
        AgentAction.Back, AgentAction.Home, is AgentAction.Wait, is AgentAction.Finish, is AgentAction.Fail,
        is AgentAction.LaunchApp -> true
        else -> false
    }

    fun validateGoal(goal: String): Result<Unit> = runCatching {
        matchGoal(goal)?.let { error("Sensitive operation blocked before model access: ${it.term}") }
    }

    fun validateAction(action: AgentAction, observation: Observation, goal: GoalContext? = null): Result<Unit> = runCatching {
        if (goal != null) {
            matchGoal(goal.originalGoal)?.let {
                require(isSafeRecoveryAction(action)) { "Sensitive goal permits recovery or termination actions only" }
            }
        }
        matchObservation(observation)?.let {
            require(isSafeRecoveryAction(action)) { "Sensitive page permits recovery or termination actions only" }
        }
        matchAction(action)?.let { error("Sensitive action blocked: ${it.term}") }
        targetMatch(action, observation)?.let { error("Sensitive action target blocked: ${it.term}") }
    }

    private fun selectorMatch(selector: ElementSelector?, source: String): Match? = selector
        ?.let { listOf(it.text, it.description, it.viewIdResourceName, it.className).filterNotNull().firstNotNullOfOrNull { value -> matchText(value, source) } }

    private fun targetMatch(action: AgentAction, observation: Observation): Match? {
        val node = when (action) {
            is AgentAction.ClickNode -> NodeSelector.resolve(observation, action.nodeId, action.selector)
            is AgentAction.EnsureToggle -> NodeSelector.resolve(observation, action.nodeId, action.selector)
            is AgentAction.InputText -> if (action.nodeId != null || action.target != null) {
                NodeSelector.resolve(observation, action.nodeId, action.target)
            } else {
                observation.nodes.singleOrNull { it.visible && it.focused && it.editable }
            }
            is AgentAction.SubmitInput -> if (action.nodeId != null || action.target != null) {
                NodeSelector.resolve(observation, action.nodeId, action.target)
            } else {
                observation.nodes.singleOrNull { it.visible && it.focused && it.editable }
            }
            else -> null
        }
        val values = node?.let { listOf(it.text, it.description, it.viewIdResourceName, it.className) }.orEmpty()
        return values.filter(String::isNotBlank).firstNotNullOfOrNull { value -> matchText(value, "target") }
    }
}
