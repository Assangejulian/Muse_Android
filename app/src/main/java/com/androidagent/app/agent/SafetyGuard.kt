package com.androidagent.app.agent

object SafetyGuard {
    fun validate(
        action: AgentAction,
        observation: Observation,
        packagePolicy: PackagePolicy,
        launchablePackages: Set<String>,
        goal: GoalContext? = null,
    ): Result<AgentAction> = runCatching {
        val recoveryAction = SensitiveOperationPolicy.isSafeRecoveryAction(action)
        val observedAllowed = observation.packageName.isBlank() || packagePolicy.allows(observation.packageName)
        if (packagePolicy.primaryPackage != null && !recoveryAction && !observedAllowed) {
            require(packagePolicy.allowTemporaryExternalPackages && action is AgentAction.LaunchApp) {
                "Package is outside the current package policy"
            }
        }
        SensitiveOperationPolicy.validateAction(action, observation, goal).getOrThrow()
        when (action) {
            is AgentAction.LaunchApp -> {
                require(action.packageName in launchablePackages) { "Package is not in the installed app catalog" }
                val canEstablishPrimary = packagePolicy.primaryPackage.isNullOrBlank() &&
                    (packagePolicy.allowedPackages.isEmpty() || action.packageName in packagePolicy.allowedPackages)
                require(canEstablishPrimary || packagePolicy.allows(action.packageName)) {
                    "Package is not allowed by the current plan"
                }
            }
            is AgentAction.ClickText -> Unit
            is AgentAction.TapPoint -> require(action.x in 30..970 && action.y in 30..970) { "Visual point is outside the safe screen region" }
            is AgentAction.InputText -> {
                require(action.text.length <= 2_000) { "Input is too long" }
                Unit
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
