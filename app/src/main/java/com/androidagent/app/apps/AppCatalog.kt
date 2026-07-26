package com.androidagent.app.apps

import android.content.Context
import android.content.Intent
import android.os.SystemClock

data class LaunchableApp(val label: String, val packageName: String)

class AppCatalog(context: Context) {
    private val applicationContext = context.applicationContext

    fun list(): List<LaunchableApp> {
        cached()?.let { return it }
        return synchronized(lock) {
            cached() ?: query().also {
                cache = it
                cachedAtElapsedRealtime = SystemClock.elapsedRealtime()
            }
        }
    }

    private fun cached(): List<LaunchableApp>? =
        cache?.takeIf {
            SystemClock.elapsedRealtime() - cachedAtElapsedRealtime in 0..CACHE_MILLIS
        }

    private fun query(): List<LaunchableApp> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val packageManager = applicationContext.packageManager
        return packageManager.queryIntentActivities(intent, 0)
            .map { LaunchableApp(it.loadLabel(packageManager).toString(), it.activityInfo.packageName) }
            .distinctBy { it.packageName }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
    }

    fun compactList(): String = list().joinToString("\n") { "${it.label} | ${it.packageName}" }.take(MAX_CATALOG_CHARS)

    fun isLaunchable(packageName: String): Boolean = list().any { it.packageName == packageName }

    companion object {
        /**
         * Enumerating launcher activities costs one PackageManager query plus a
         * resource load per installed app, and callers ask for it on the UI thread
         * before every message. A short process-wide cache keeps that path cheap
         * while still picking up package changes without a permanent stale list.
         */
        private const val CACHE_MILLIS = 5 * 60 * 1_000L
        private const val MAX_CATALOG_CHARS = 16_000

        private val lock = Any()

        @Volatile
        private var cache: List<LaunchableApp>? = null

        @Volatile
        private var cachedAtElapsedRealtime = 0L
    }
}
