package com.androidagent.app.apps

import android.content.Context
import android.content.Intent

data class LaunchableApp(val label: String, val packageName: String)

class AppCatalog(private val context: Context) {
    fun list(): List<LaunchableApp> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return context.packageManager.queryIntentActivities(intent, 0)
            .map { LaunchableApp(it.loadLabel(context.packageManager).toString(), it.activityInfo.packageName) }
            .distinctBy { it.packageName }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
    }

    fun compactList(): String = list().joinToString("\n") { "${it.label} | ${it.packageName}" }.take(16_000)

    fun isLaunchable(packageName: String): Boolean = list().any { it.packageName == packageName }
}
