package com.androidagent.app.accessibility

object ObservationPolicy {
    private const val AGENT_PACKAGE = "com.androidagent.app"

    fun shouldIncludePackage(packageName: String?): Boolean =
        packageName.isNullOrBlank() || packageName != AGENT_PACKAGE
}
