package com.androidagent.app.privileged

object PrivilegedDeviceBackend {
    suspend fun foregroundPackage(): String? {
        if (!ShizukuBridge.isReady()) return null
        val result = ShizukuBridge.execute(PrivilegedCommandBuilder.foregroundPackage(), 3_000L)
        if (!result.success) return null
        return result.stdout.lineSequence()
            .map(String::trim)
            .firstOrNull { PACKAGE_NAME.matches(it) }
    }

    suspend fun launchPackage(packageName: String): Boolean =
        execute(PrivilegedCommandBuilder.launchPackage(packageName), 5_000L)

    suspend fun tap(x: Int, y: Int): Boolean =
        execute(PrivilegedCommandBuilder.tap(x, y), 3_000L)

    suspend fun swipe(startX: Int, startY: Int, endX: Int, endY: Int, durationMillis: Int): Boolean =
        execute(PrivilegedCommandBuilder.swipe(startX, startY, endX, endY, durationMillis), 4_000L)

    suspend fun keyEvent(keyCode: Int): Boolean =
        execute(PrivilegedCommandBuilder.keyEvent(keyCode), 3_000L)

    private suspend fun execute(command: String?, timeoutMillis: Long): Boolean {
        if (command == null || !ShizukuBridge.isReady()) return false
        return ShizukuBridge.execute(command, timeoutMillis).success
    }

    private val PACKAGE_NAME = Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+")
}

internal object PrivilegedCommandBuilder {
    private val packageName = Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+")

    fun foregroundPackage(): String =
        "dumpsys activity activities | sed -n " +
            "'s/.*mResumedActivity:.* \\([^/ ]*\\)\\/.*/\\1/p;" +
            "s/.*topResumedActivity=.* \\([^/ ]*\\)\\/.*/\\1/p' | head -n 1"

    fun launchPackage(value: String): String? {
        if (!packageName.matches(value)) return null
        return "component=\"\$(cmd package resolve-activity --brief -a android.intent.action.MAIN " +
            "-c android.intent.category.LAUNCHER $value 2>/dev/null | tail -n 1)\"; " +
            "[ -n \"\$component\" ] && am start -n \"\$component\""
    }

    fun tap(x: Int, y: Int): String? =
        if (x >= 0 && y >= 0) "input tap $x $y" else null

    fun swipe(startX: Int, startY: Int, endX: Int, endY: Int, durationMillis: Int): String? =
        if (minOf(startX, startY, endX, endY) >= 0 && durationMillis in 50..5_000) {
            "input swipe $startX $startY $endX $endY $durationMillis"
        } else {
            null
        }

    fun keyEvent(keyCode: Int): String? =
        if (keyCode in 0..300) "input keyevent $keyCode" else null
}
