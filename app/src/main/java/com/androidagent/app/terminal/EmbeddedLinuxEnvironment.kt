package com.androidagent.app.terminal

import android.content.Context
import android.os.Build
import com.androidagent.app.BuildConfig
import com.androidagent.app.privileged.PrivilegedCommandResult
import com.androidagent.app.privileged.ShizukuBridge
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class EnvironmentMirror(
    val id: String,
    val label: String,
    val rootfsBaseUrl: String,
    val aptBaseUrl: String,
)

data class InstalledLinuxEnvironment(
    val version: String,
    val mirrorId: String,
    val tools: Set<String>,
)

data class EnvironmentInstallProgress(
    val fraction: Float?,
    val message: String,
)

object EmbeddedLinuxEnvironment {
    const val ROOTFS_VERSION = "24.04.4"
    const val ROOTFS_FILE = "ubuntu-base-24.04.4-base-arm64.tar.gz"
    const val ROOTFS_SHA256 = "04207713ece899c3740823d33690441ad3a7f0ded1101aca744e2b0f37ac7ff2"
    const val SHIM_PATH = "/data/local/tmp/muse/shims"

    val mirrors = listOf(
        EnvironmentMirror(
            id = "tuna",
            label = "TUNA 清华",
            rootfsBaseUrl = "https://mirrors.tuna.tsinghua.edu.cn/ubuntu-cdimage/ubuntu-base/releases/24.04/release",
            aptBaseUrl = "https://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports",
        ),
        EnvironmentMirror(
            id = "ustc",
            label = "USTC 中科大",
            rootfsBaseUrl = "https://mirrors.ustc.edu.cn/ubuntu-cdimage/ubuntu-base/releases/24.04/release",
            aptBaseUrl = "https://mirrors.ustc.edu.cn/ubuntu-ports",
        ),
        EnvironmentMirror(
            id = "bfsu",
            label = "BFSU 北外",
            rootfsBaseUrl = "https://mirrors.bfsu.edu.cn/ubuntu-cdimage/ubuntu-base/releases/24.04/release",
            aptBaseUrl = "https://mirrors.bfsu.edu.cn/ubuntu-ports",
        ),
    )

    suspend fun inspect(): InstalledLinuxEnvironment? {
        if (!ShizukuBridge.isReady()) return null
        val result = ShizukuBridge.execute(
            "test -f /data/local/tmp/muse/install.json && cat /data/local/tmp/muse/install.json",
            5_000L,
        )
        if (!result.success || result.stdout.isBlank()) return null
        return parseManifest(result.stdout)
    }

    suspend fun install(
        context: Context,
        mirror: EnvironmentMirror,
        tools: Set<String>,
        onProgress: (EnvironmentInstallProgress) -> Unit,
    ): PrivilegedCommandResult = withContext(Dispatchers.IO) {
        require(Build.SUPPORTED_ABIS.any { it == "arm64-v8a" }) { "当前设备不是 arm64-v8a，暂不支持此运行时" }
        require(mirror in mirrors) { "未知镜像源" }
        val selectedTools = tools.intersect(INSTALLABLE_TOOLS)
        require(selectedTools.isNotEmpty()) { "请至少选择一个要安装的环境组件" }
        require(ShizukuBridge.isReady()) { "请先连接 Shizuku 控制终端" }

        val externalFiles = requireNotNull(context.getExternalFilesDir(null)) { "外部应用目录不可用" }
        val downloadDir = File(externalFiles, "environment-installer").apply { mkdirs() }
        val archive = File(downloadDir, ROOTFS_FILE)
        onProgress(EnvironmentInstallProgress(0f, "准备从 ${mirror.label} 下载 Ubuntu Base"))
        if (!archive.isFile || sha256(archive) != ROOTFS_SHA256) {
            download(mirror, archive, onProgress)
        } else {
            onProgress(EnvironmentInstallProgress(0.68f, "已命中校验通过的下载缓存"))
        }
        currentCoroutineContext().ensureActive()
        check(sha256(archive) == ROOTFS_SHA256) { "Ubuntu Base SHA-256 校验失败" }
        onProgress(EnvironmentInstallProgress(null, "校验通过，正在解压并通过 ${mirror.label} 安装组件（可能需要数分钟）"))

        val result = ShizukuBridge.installEnvironment(
            archivePath = archive.absolutePath,
            mirrorId = mirror.id,
            toolIds = selectedTools.sorted().joinToString(","),
        )
        if (result.success) {
            onProgress(EnvironmentInstallProgress(1f, "Ubuntu 环境与终端命令已安装"))
        }
        result
    }

    internal fun parseManifest(raw: String): InstalledLinuxEnvironment? = runCatching {
        val json = JSONObject(raw.trim())
        InstalledLinuxEnvironment(
            version = json.getString("version"),
            mirrorId = json.getString("mirror"),
            tools = json.optJSONArray("tools")?.let { array ->
                buildSet { repeat(array.length()) { add(array.getString(it)) } }
            }.orEmpty(),
        )
    }.getOrNull()

    internal fun mirrorById(id: String): EnvironmentMirror? = mirrors.firstOrNull { it.id == id }

    private suspend fun download(
        mirror: EnvironmentMirror,
        destination: File,
        onProgress: (EnvironmentInstallProgress) -> Unit,
    ) {
        val partial = File(destination.parentFile, "${destination.name}.part")
        partial.delete()
        val request = Request.Builder()
            .url("${mirror.rootfsBaseUrl}/$ROOTFS_FILE")
            .header("User-Agent", rootfsUserAgent(BuildConfig.VERSION_NAME))
            .header("Accept", "application/octet-stream")
            .build()
        val call = HTTP_CLIENT.newCall(request)
        val response = call.await()
        try {
            check(response.isSuccessful) { "镜像下载失败：HTTP ${response.code}" }
            val body = checkNotNull(response.body) { "镜像返回空文件" }
            val total = body.contentLength()
            body.byteStream().use { input ->
                FileOutputStream(partial).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var copied = 0L
                    var lastPercent = -1L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        copied += count
                        val fraction = if (total > 0) (copied.toFloat() / total).coerceIn(0f, 1f) else null
                        val percent = if (total > 0) (copied * 100 / total).coerceAtMost(100) else -1L
                        if (percent != lastPercent) {
                            lastPercent = percent
                            onProgress(
                                EnvironmentInstallProgress(
                                    fraction = fraction?.times(0.68f),
                                    message = if (total > 0) "下载 Ubuntu Base $percent%" else "下载 Ubuntu Base",
                                ),
                            )
                        }
                    }
                    output.fd.sync()
                }
            }
            check(sha256(partial) == ROOTFS_SHA256) { "Ubuntu Base SHA-256 校验失败" }
            if (destination.exists()) check(destination.delete()) { "无法替换旧下载缓存" }
            check(partial.renameTo(destination)) { "无法保存下载文件" }
        } finally {
            response.close()
            if (partial.exists()) partial.delete()
        }
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
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }

            override fun onResponse(call: Call, response: Response) {
                if (continuation.isActive) continuation.resume(response) { _, value, _ -> value.close() }
                else response.close()
            }
        })
    }

    private val INSTALLABLE_TOOLS = setOf("ssh", "python", "node", "java")
    private val HTTP_CLIENT = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.MINUTES)
        .build()
}

internal fun rootfsUserAgent(version: String): String =
    "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 Chrome/138.0 Mobile Safari/537.36 Muse/$version"
