package com.androidagent.app.update

import android.content.Context
import android.content.Intent
import android.os.Environment
import android.provider.Settings
import androidx.core.content.FileProvider
import com.androidagent.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

data class UpdateInfo(val version: String, val apkUrl: String, val notes: String)
data class DownloadProgress(val downloadedBytes: Long, val totalBytes: Long) {
    val fraction: Float get() = if (totalBytes > 0) (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
    val percent: Int get() = (fraction * 100).toInt()
}

class GitHubUpdater(private val context: Context) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    suspend fun check(repository: String): UpdateInfo? = withContext(Dispatchers.IO) {
        if (!repository.matches(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+"))) return@withContext null
        val request = Request.Builder()
            .url("https://api.github.com/repos/$repository/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "AndroidAgent/${BuildConfig.VERSION_NAME}")
            .build()
        client.newCall(request).execute().use { response ->
            if (response.code == 404) return@withContext null
            require(response.isSuccessful) { "GitHub update check failed: HTTP ${response.code}" }
            val json = JSONObject(response.body?.string().orEmpty())
            val latest = json.getString("tag_name").removePrefix("v")
            if (compareVersionNames(latest, BuildConfig.VERSION_NAME) <= 0) return@withContext null
            val assets = json.getJSONArray("assets")
            val apkUrl = (0 until assets.length()).map { assets.getJSONObject(it) }
                .firstOrNull { it.optString("name").endsWith(".apk", true) }
                ?.getString("browser_download_url") ?: return@withContext null
            UpdateInfo(latest, apkUrl, json.optString("body"))
        }
    }

    suspend fun downloadAndInstall(update: UpdateInfo, onProgress: suspend (DownloadProgress) -> Unit = {}) = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(update.apkUrl).header("User-Agent", "AndroidAgent/${BuildConfig.VERSION_NAME}").build()
        client.newCall(request).execute().use { response ->
            require(response.isSuccessful) { "APK download failed: HTTP ${response.code}" }
            val directory = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: error("Download directory unavailable")
            val apk = File(directory, "AndroidAgent-${update.version}.apk")
            val body = response.body ?: error("APK download returned an empty body")
            val total = body.contentLength()
            var downloaded = 0L
            var lastReported = 0L
            body.byteStream().use { input ->
                apk.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        downloaded += count
                        if (downloaded - lastReported >= 256 * 1024 || downloaded == total) {
                            lastReported = downloaded
                            withContext(Dispatchers.Main) { onProgress(DownloadProgress(downloaded, total)) }
                        }
                    }
                }
            }
            withContext(Dispatchers.Main) { openInstaller(apk) }
        }
    }

    private fun openInstaller(apk: File) {
        if (!context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = android.net.Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            return
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
        context.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        })
    }

}

internal fun compareVersionNames(left: String, right: String): Int {
    val a = left.split('.').map { it.toIntOrNull() ?: 0 }
    val b = right.split('.').map { it.toIntOrNull() ?: 0 }
    for (index in 0 until maxOf(a.size, b.size)) {
        val comparison = a.getOrElse(index) { 0 }.compareTo(b.getOrElse(index) { 0 })
        if (comparison != 0) return comparison
    }
    return 0
}
