package com.androidagent.app.privileged

import android.content.Context
import android.os.SystemClock
import android.system.Os
import androidx.annotation.Keep
import org.json.JSONObject
import java.io.InputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * Runs inside Shizuku's UserService process with ADB shell or root identity.
 * The app process communicates with it only through the generated Binder API.
 */
class MusePrivilegedService() : IMusePrivilegedService.Stub() {
    private val streamExecutor: ExecutorService = Executors.newCachedThreadPool()
    private var serviceContext: Context? = null

    @Keep
    constructor(context: Context) : this() {
        serviceContext = context.applicationContext
    }

    override fun destroy() {
        streamExecutor.shutdownNow()
        exitProcess(0)
    }

    override fun identity(): String = "uid=${Os.getuid()} pid=${Os.getpid()}"

    override fun installEnvironment(archivePath: String?, mirrorId: String?, toolIds: String?): String {
        val startedAt = SystemClock.elapsedRealtime()
        val context = serviceContext ?: return resultJson(
            exitCode = -1,
            stdout = "",
            stderr = "",
            timedOut = false,
            durationMillis = 0,
            error = "Privileged service context is unavailable",
        )
        val result = PrivilegedEnvironmentInstaller(context).install(
            archivePath = archivePath.orEmpty(),
            mirrorId = mirrorId.orEmpty(),
            toolIds = toolIds.orEmpty(),
        )
        return resultJson(
            exitCode = result.exitCode,
            stdout = result.stdout,
            stderr = result.stderr,
            timedOut = result.timedOut,
            durationMillis = SystemClock.elapsedRealtime() - startedAt,
            error = result.error,
        )
    }

    override fun execute(command: String?, timeoutMillis: Long): String {
        val startedAt = SystemClock.elapsedRealtime()
        val normalized = command.orEmpty().trim()
        if (normalized.isBlank() || normalized.length > MAX_COMMAND_CHARS) {
            return resultJson(
                exitCode = -1,
                stdout = "",
                stderr = "",
                timedOut = false,
                durationMillis = 0,
                error = "Command must contain 1..$MAX_COMMAND_CHARS characters",
            )
        }

        val timeout = timeoutMillis.coerceIn(MIN_TIMEOUT_MILLIS, MAX_TIMEOUT_MILLIS)
        var process: Process? = null
        var stdoutFuture: Future<String>? = null
        var stderrFuture: Future<String>? = null
        return try {
            val startedProcess = ProcessBuilder("/system/bin/sh", "-c", normalized)
                .directory(java.io.File("/"))
                .start()
            process = startedProcess
            startedProcess.outputStream.close()
            stdoutFuture = streamExecutor.submit<String> { readCapped(startedProcess.inputStream) }
            stderrFuture = streamExecutor.submit<String> { readCapped(startedProcess.errorStream) }
            val finished = startedProcess.waitFor(timeout, TimeUnit.MILLISECONDS)
            if (!finished) {
                startedProcess.destroy()
                if (!startedProcess.waitFor(PROCESS_STOP_GRACE_MILLIS, TimeUnit.MILLISECONDS)) {
                    startedProcess.destroyForcibly()
                }
            }
            val stdout = stdoutFuture.awaitOutput()
            val stderr = stderrFuture.awaitOutput()
            resultJson(
                exitCode = if (finished) startedProcess.exitValue() else -1,
                stdout = stdout,
                stderr = stderr,
                timedOut = !finished,
                durationMillis = SystemClock.elapsedRealtime() - startedAt,
                error = null,
            )
        } catch (error: Throwable) {
            runCatching { process?.destroyForcibly() }
            stdoutFuture?.cancel(true)
            stderrFuture?.cancel(true)
            resultJson(
                exitCode = -1,
                stdout = "",
                stderr = "",
                timedOut = false,
                durationMillis = SystemClock.elapsedRealtime() - startedAt,
                error = error.message ?: error::class.java.simpleName,
            )
        }
    }

    private fun readCapped(stream: InputStream): String = stream.use {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8_192)
        var retained = 0
        while (true) {
            val count = it.read(buffer)
            if (count < 0) break
            if (retained < MAX_OUTPUT_BYTES) {
                val keep = minOf(count, MAX_OUTPUT_BYTES - retained)
                output.write(buffer, 0, keep)
                retained += keep
            }
        }
        output.toString(Charsets.UTF_8.name())
    }

    private fun Future<String>?.awaitOutput(): String = if (this == null) {
        ""
    } else {
        runCatching { get(OUTPUT_JOIN_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS) }.getOrDefault("")
    }

    private fun resultJson(
        exitCode: Int,
        stdout: String,
        stderr: String,
        timedOut: Boolean,
        durationMillis: Long,
        error: String?,
    ): String = JSONObject()
        .put("exitCode", exitCode)
        .put("stdout", stdout)
        .put("stderr", stderr)
        .put("timedOut", timedOut)
        .put("durationMillis", durationMillis)
        .put("uid", Os.getuid())
        .put("error", error ?: JSONObject.NULL)
        .toString()

    private companion object {
        const val MAX_COMMAND_CHARS = 16_000
        const val MAX_OUTPUT_BYTES = 64 * 1_024
        const val MIN_TIMEOUT_MILLIS = 250L
        const val MAX_TIMEOUT_MILLIS = 30_000L
        const val PROCESS_STOP_GRACE_MILLIS = 250L
        const val OUTPUT_JOIN_TIMEOUT_MILLIS = 1_000L
    }
}
