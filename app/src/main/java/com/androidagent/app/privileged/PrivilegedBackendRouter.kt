package com.androidagent.app.privileged

import android.content.Context

object PrivilegedBackendRouter {
    fun configure(context: Context, enabled: Boolean) {
        ShizukuBridge.configure(context, enabled)
    }

    fun isReady(): Boolean = ShizukuBridge.isReady()

    suspend fun execute(command: String, timeoutMillis: Long = 5_000L): PrivilegedCommandResult =
        ShizukuBridge.execute(command, timeoutMillis)

    suspend fun testConnection(): PrivilegedCommandResult = execute(
        "id; getprop ro.build.version.release",
        3_000L,
    )
}
