package com.androidagent.app.agent

object SafetyGuard {
    private val blockedTerms = listOf(
        "payment", "checkout", "purchase", "bank card", "verification code", "one-time password",
        "grant permission", "install app", "uninstall app",
    )

    fun validate(
        action: AgentAction,
        observation: Observation,
        packagePolicy: PackagePolicy,
        launchablePackages: Set<String>,
    ): Result<AgentAction> = runCatching {
        val recoveryAction = action is AgentAction.Back || action is AgentAction.Wait || action is AgentAction.LaunchApp
        val observedAllowed = observation.packageName.isBlank() || packagePolicy.allows(observation.packageName)
        if (packagePolicy.primaryPackage != null && !recoveryAction && !observedAllowed) {
            require(packagePolicy.allowTemporaryExternalPackages && action is AgentAction.LaunchApp) {
                "Package is outside the current package policy"
            }
        }
        if (!recoveryAction) {
            require(blockedTerms.none { term -> observation.compactText().contains(term, ignoreCase = true) }) {
                "Sensitive page detected"
            }
        }
        when (action) {
            is AgentAction.LaunchApp -> {
                require(action.packageName in launchablePackages) { "Package is not in the installed app catalog" }
                val allowed = action.packageName == packagePolicy.primaryPackage || action.packageName in packagePolicy.allowedPackages
                require(allowed || packagePolicy.primaryPackage == null) { "Package is not allowed by the current plan" }
            }
            is AgentAction.ClickText -> require(blockedTerms.none { action.text.contains(it, true) }) { "Sensitive click rejected" }
            is AgentAction.TapPoint -> require(action.x in 30..970 && action.y in 30..970) { "Visual point is outside the safe screen region" }
            is AgentAction.InputText -> {
                require(action.text.length <= 2_000) { "Input is too long" }
                require(blockedTerms.none { action.text.contains(it, true) }) { "Sensitive input rejected" }
            }
            is AgentAction.Swipe -> require(action.direction in setOf("up", "down", "left", "right")) { "Invalid direction" }
            is AgentAction.EnsureToggle -> require(action.nodeId > 0) { "Invalid toggle target" }
            else -> Unit
        }
        action
    }

    fun validate(
        action: AgentAction,
        observation: Observation,
        allowedPackage: String?,
        launchablePackages: Set<String>,
    ): Result<AgentAction> = validate(
        action,
        observation,
        PackagePolicy(
            allowedPackages = allowedPackage?.takeIf(String::isNotBlank)?.let { mutableSetOf(it) } ?: mutableSetOf(),
            primaryPackage = allowedPackage?.takeIf(String::isNotBlank),
        ),
        launchablePackages,
    )
}
