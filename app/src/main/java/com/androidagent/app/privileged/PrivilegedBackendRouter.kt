package com.androidagent.app.privileged

import android.content.Context

object PrivilegedBackendRouter {
    fun configure(context: Context, enabled: Boolean) {
        EmbeddedAdbBridge.configure(context, enabled)
        ShizukuBridge.configure(context, enabled)
    }

    fun isReady(): Boolean = EmbeddedAdbBridge.isReady() || ShizukuBridge.isReady()

    suspend fun execute(command: String, timeoutMillis: Long = 5_000L): PrivilegedCommandResult {
        if (EmbeddedAdbBridge.isReady()) {
            val embedded = EmbeddedAdbBridge.execute(command, timeoutMillis)
            if (embedded.success || !ShizukuBridge.isReady()) return embedded
        }
        return ShizukuBridge.execute(command, timeoutMillis)
    }

    suspend fun testConnection(): PrivilegedCommandResult = execute(
        "id; getprop ro.build.version.release",
        3_000L,
    )
}
