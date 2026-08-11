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
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

data class UpdateInfo(
    val version: String,
    val apkUrl: String,
    val notes: String,
    val sha256: String,
    val sizeBytes: Long,
)

data class DownloadProgress(val downloadedBytes: Long, val totalBytes: Long) {
    val fraction: Float get() = if (totalBytes > 0) (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
    val percent: Int get() = (fraction * 100).toInt()
}

enum class InstallerLaunchResult {
    INSTALLER_OPENED,
    PERMISSION_REQUIRED,
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
            parseLatestRelease(response.body?.string().orEmpty(), BuildConfig.VERSION_NAME)
        }
    }

    suspend fun downloadAndInstall(
        update: UpdateInfo,
        onProgress: suspend (DownloadProgress) -> Unit = {},
    ): InstallerLaunchResult = withContext(Dispatchers.IO) {
        require(update.apkUrl.startsWith("https://github.com/")) { "Release APK URL is not trusted" }
        require(SHA256_PATTERN.matches(update.sha256)) { "Release APK SHA-256 is invalid" }
        val directory = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: error("Download directory unavailable")
        val apk = File(directory, "Muse-${update.version}.apk")
        if (apk.isFile && sha256(apk) == update.sha256) {
            withContext(Dispatchers.Main) { onProgress(DownloadProgress(apk.length(), apk.length())) }
            return@withContext openInstaller(apk)
        }

        val partial = File(directory, "${apk.name}.part")
        partial.delete()
        val request = Request.Builder().url(update.apkUrl).header("User-Agent", "AndroidAgent/${BuildConfig.VERSION_NAME}").build()
        try {
            client.newCall(request).execute().use { response ->
                require(response.isSuccessful) { "APK download failed: HTTP ${response.code}" }
                val body = response.body ?: error("APK download returned an empty body")
                val total = body.contentLength().takeIf { it > 0 } ?: update.sizeBytes
                val digest = MessageDigest.getInstance("SHA-256")
                var downloaded = 0L
                var lastReported = 0L
                body.byteStream().use { input ->
                    partial.outputStream().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            digest.update(buffer, 0, count)
                            downloaded += count
                            if (downloaded - lastReported >= 256 * 1024 || downloaded == total) {
                                lastReported = downloaded
                                withContext(Dispatchers.Main) { onProgress(DownloadProgress(downloaded, total)) }
                            }
                        }
                        output.fd.sync()
                    }
                }
                if (update.sizeBytes > 0) require(downloaded == update.sizeBytes) { "Downloaded APK size mismatch" }
                val actualDigest = digest.digest().toHex()
                require(actualDigest == update.sha256) { "Downloaded APK SHA-256 mismatch" }
            }
            if (apk.exists()) require(apk.delete()) { "Could not replace the previous APK download" }
            require(partial.renameTo(apk)) { "Could not finalize the APK download" }
            withContext(Dispatchers.Main) { openInstaller(apk) }
        } finally {
            if (partial.exists()) partial.delete()
        }
    }

    private fun openInstaller(apk: File): InstallerLaunchResult {
        if (!context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = android.net.Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            return InstallerLaunchResult.PERMISSION_REQUIRED
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
        context.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        })
        return InstallerLaunchResult.INSTALLER_OPENED
    }
}

internal fun parseLatestRelease(payload: String, currentVersion: String): UpdateInfo? {
    val json = JSONObject(payload)
    val latest = json.getString("tag_name").removePrefix("v")
    if (compareVersionNames(latest, currentVersion) <= 0) return null
    val assets = json.getJSONArray("assets")
    val asset = (0 until assets.length()).map { assets.getJSONObject(it) }
        .firstOrNull { it.optString("name").endsWith(".apk", true) }
        ?: return null
    val digest = asset.optString("digest").removePrefix("sha256:").lowercase()
    require(SHA256_PATTERN.matches(digest)) { "Release APK is missing a valid SHA-256 digest" }
    return UpdateInfo(
        version = latest,
        apkUrl = asset.getString("browser_download_url"),
        notes = json.optString("body"),
        sha256 = digest,
        sizeBytes = asset.optLong("size").coerceAtLeast(0L),
    )
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

private fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    FileInputStream(file).use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().toHex()
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

private val SHA256_PATTERN = Regex("[a-f0-9]{64}")
