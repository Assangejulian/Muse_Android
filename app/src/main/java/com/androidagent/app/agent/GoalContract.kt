package com.androidagent.app.agent

internal object GoalContract {
    private val latestCreator = Regex("(?:给|搜索|查找)(.+?)(?:的)?最新")
    private val explicitSearch = Regex("(?:搜索|查找)([^，。,.]+)")

    fun extractSearchQuery(goal: String): String? {
        val candidate = latestCreator.find(goal)?.groupValues?.get(1)
            ?: explicitSearch.find(goal)?.groupValues?.get(1)
        return candidate?.trim()?.removePrefix("一下")?.takeIf { it.isNotBlank() }
    }
}
