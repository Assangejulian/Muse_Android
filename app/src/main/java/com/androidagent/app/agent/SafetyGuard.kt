package com.androidagent.app.agent

object SafetyGuard {
    private val blockedTerms = listOf(
        "支付", "付款", "购买", "充值", "转账", "银行卡", "验证码", "实名认证",
        "修改密码", "解绑", "pay", "purchase", "bank card", "verification code",
    )

    fun validate(
        action: AgentAction,
        observation: Observation,
        allowedPackage: String?,
        launchablePackages: Set<String>,
    ): Result<AgentAction> = runCatching {
        val recoveryAction = action is AgentAction.Back || action is AgentAction.Wait || action is AgentAction.LaunchApp
        val packageAllowed = observation.packageName.isBlank() ||
            observation.packageName == allowedPackage ||
            observation.packageName == "com.androidagent.app" ||
            observation.packageName.startsWith("com.android.systemui")
        if (allowedPackage != null && !recoveryAction) {
            require(packageAllowed) { "Package left allowlist: ${observation.packageName}" }
        }
        if (!recoveryAction) {
            require(blockedTerms.none { term -> observation.compactText().contains(term, ignoreCase = true) }) {
                "Sensitive page detected"
            }
        }
        when (action) {
            is AgentAction.LaunchApp -> {
                require(action.packageName in launchablePackages) { "Package is not in the installed app catalog" }
                if (allowedPackage != null) require(action.packageName == allowedPackage) { "Package is not allowlisted" }
            }
            is AgentAction.ClickText -> require(blockedTerms.none { action.text.contains(it, true) }) { "Sensitive click rejected" }
            is AgentAction.TapPoint -> {
                require(action.x in 30..970 && action.y in 30..970) { "Visual point is outside the safe screen region" }
            }
            is AgentAction.InputText -> {
                require(action.text.length <= 300) { "Input is too long" }
                require(blockedTerms.none { action.text.contains(it, true) }) { "Sensitive input rejected" }
            }
            is AgentAction.Swipe -> require(action.direction in setOf("up", "down", "left", "right")) { "Invalid direction" }
            is AgentAction.EnsureToggle -> require(action.nodeId > 0) { "Invalid toggle target" }
            else -> Unit
        }
        action
    }
}
