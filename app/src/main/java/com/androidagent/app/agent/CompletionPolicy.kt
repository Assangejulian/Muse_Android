package com.androidagent.app.agent

object CompletionPolicy {
    fun hasMinimumEvidence(goal: String, history: List<String>): Boolean {
        val actions = history.filterNot { it.startsWith("BLOCKED_") || it.startsWith("FAILED_") || it.startsWith("REJECTED_") }
        val launched = actions.any { it.contains("LaunchApp") }
        if (!launched) return false

        val normalized = goal.lowercase()
        val clicks = actions.count { it.contains("ClickText") || it.contains("ClickNode") }
        val searches = actions.count { it.contains("InputText") }
        val onlyOpen = (normalized.startsWith("打开") || normalized.startsWith("启动")) &&
            listOf("点赞", "搜索", "最新", "领取", "签到", "播放", "关注", "收藏", "进入").none(normalized::contains)
        if (onlyOpen) return true
        if (normalized.contains("点赞")) return clicks >= 2 && (searches >= 1 || clicks >= 3)
        if (normalized.contains("搜索") || normalized.contains("查找")) return searches >= 1 && clicks >= 1
        if (normalized.contains("领取") || normalized.contains("签到") || normalized.contains("金币")) return clicks >= 1
        return actions.size >= 2
    }
}
