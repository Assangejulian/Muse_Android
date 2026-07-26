package com.androidagent.app.privileged

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import com.androidagent.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import org.json.JSONObject
import rikka.shizuku.Shizuku

data class ShizukuBridgeState(
    val enabled: Boolean = false,
    val binderAvailable: Boolean = false,
    val permissionGranted: Boolean = false,
    val connecting: Boolean = false,
    val connected: Boolean = false,
    val serverUid: Int? = null,
    val serverVersion: Int? = null,
    val detail: String = "Shizuku is not connected",
)

data class PrivilegedCommandResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean,
    val durationMillis: Long,
    val uid: Int?,
    val error: String? = null,
) {
    val success: Boolean get() = exitCode == 0 && !timedOut && error == null

    fun displayText(): String = buildString {
        append("exit=").append(exitCode)
        uid?.let { append(" · uid=").append(it) }
        append(" · ").append(durationMillis).append(" ms")
        if (timedOut) append("\nTimed out")
        if (!error.isNullOrBlank()) append("\n").append(error)
        if (stdout.isNotBlank()) append("\n\n").append(stdout.trim())
        if (stderr.isNotBlank()) append("\n\nstderr:\n").append(stderr.trim())
    }.take(MAX_DISPLAY_CHARS)

    companion object {
        private const val MAX_DISPLAY_CHARS = 12_000

        fun failure(message: String): PrivilegedCommandResult =
            PrivilegedCommandResult(-1, "", "", false, 0, null, message)

        fun parse(raw: String): PrivilegedCommandResult {
            val json = JSONObject(raw)
            return PrivilegedCommandResult(
                exitCode = json.optInt("exitCode", -1),
                stdout = json.optString("stdout"),
                stderr = json.optString("stderr"),
                timedOut = json.optBoolean("timedOut"),
                durationMillis = json.optLong("durationMillis"),
                uid = json.optInt("uid").takeIf { json.has("uid") },
                error = json.optString("error").takeUnless { it.isBlank() || it == "null" },
            )
        }
    }
}

object ShizukuBridge {
    private const val TAG = "AndroidAgent"
    private const val REQUEST_PERMISSION_CODE = 4212
    private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

    private val mutableState = MutableStateFlow(ShizukuBridgeState())
    val state: StateFlow<ShizukuBridgeState> = mutableState.asStateFlow()

    @Volatile
    private var initialized = false

    @Volatile
    private var enabled = false

    @Volatile
    private var bound = false

    @Volatile
    private var service: IMusePrivilegedService? = null

    private lateinit var applicationContext: Context

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        refreshState("Shizuku binder received")
        if (enabled) connect()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        service = null
        bound = false
        mutableState.update {
            it.copy(
                binderAvailable = false,
                permissionGranted = false,
                connecting = false,
                connected = false,
                serverUid = null,
                serverVersion = null,
                detail = "Shizuku service stopped",
            )
        }
    }

    private val permissionResultListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == REQUEST_PERMISSION_CODE) {
            val granted = grantResult == PackageManager.PERMISSION_GRANTED
            mutableState.update {
                it.copy(
                    permissionGranted = granted,
                    detail = if (granted) "Shizuku permission granted" else "Shizuku permission denied",
                )
            }
            if (granted && enabled) connect()
        }
    }

    private val userServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val connectedService = IMusePrivilegedService.Stub.asInterface(binder)
            if (connectedService == null || !binder.pingBinder()) {
                service = null
                bound = false
                mutableState.update { it.copy(connecting = false, connected = false, detail = "Invalid privileged service binder") }
                return
            }
            service = connectedService
            bound = true
            val identity = runCatching { connectedService.identity() }.getOrDefault("Privileged service connected")
            mutableState.update {
                it.copy(
                    connecting = false,
                    connected = true,
                    detail = identity,
                )
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            bound = false
            mutableState.update { it.copy(connecting = false, connected = false, detail = "Privileged service disconnected") }
        }
    }

    @Synchronized
    fun initialize(context: Context) {
        if (initialized) return
        applicationContext = context.applicationContext
        initialized = true
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(permissionResultListener)
        refreshState()
    }

    fun configure(context: Context, shouldEnable: Boolean) {
        initialize(context)
        enabled = shouldEnable
        mutableState.update { it.copy(enabled = shouldEnable) }
        if (shouldEnable) {
            refreshState()
            connect()
        } else {
            disconnect()
        }
    }

    fun requestPermission() {
        if (!initialized) return
        refreshState()
        if (!mutableState.value.binderAvailable) {
            mutableState.update { it.copy(detail = "Install and start Shizuku first") }
            return
        }
        if (mutableState.value.permissionGranted) {
            connect()
            return
        }
        runCatching {
            if (Shizuku.isPreV11()) {
                mutableState.update { it.copy(detail = "Shizuku pre-v11 is unsupported") }
            } else if (Shizuku.shouldShowRequestPermissionRationale()) {
                mutableState.update { it.copy(detail = "Shizuku permission was permanently denied") }
            } else {
                Shizuku.requestPermission(REQUEST_PERMISSION_CODE)
                mutableState.update { it.copy(detail = "Waiting for Shizuku permission") }
            }
        }.onFailure { error ->
            mutableState.update { it.copy(detail = "Permission request failed: ${error.message.orEmpty()}") }
        }
    }

    @Synchronized
    fun connect() {
        if (!initialized || !enabled || service != null || bound || mutableState.value.connecting) return
        refreshState()
        if (!mutableState.value.binderAvailable || !mutableState.value.permissionGranted) return
        mutableState.update { it.copy(connecting = true, detail = "Connecting privileged service") }
        runCatching {
            Shizuku.bindUserService(userServiceArgs(), userServiceConnection)
        }.onFailure { error ->
            bound = false
            mutableState.update {
                it.copy(connecting = false, connected = false, detail = "Privileged service connection failed: ${error.message.orEmpty()}")
            }
        }
    }

    @Synchronized
    fun disconnect() {
        val wasBound = bound || service != null
        service = null
        bound = false
        if (initialized && wasBound) {
            runCatching { Shizuku.unbindUserService(userServiceArgs(), userServiceConnection, true) }
                .onFailure { Log.w(TAG, "Could not unbind Shizuku UserService", it) }
        }
        mutableState.update {
            it.copy(connecting = false, connected = false, detail = if (enabled) "Privileged service disconnected" else "Privileged backend disabled")
        }
    }

    fun openManager(context: Context): Boolean {
        val launch = context.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE) ?: return false
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(launch); true }.getOrDefault(false)
    }

    suspend fun execute(command: String, timeoutMillis: Long = 5_000L): PrivilegedCommandResult =
        withContext(Dispatchers.IO) {
            if (!enabled) return@withContext PrivilegedCommandResult.failure("Privileged backend is disabled")
            val remote = service ?: return@withContext PrivilegedCommandResult.failure("Shizuku privileged service is not connected")
            runCatching { PrivilegedCommandResult.parse(remote.execute(command, timeoutMillis)) }
                .getOrElse { error ->
                    service = null
                    bound = false
                    mutableState.update { it.copy(connected = false, detail = "Privileged command failed: ${error.message.orEmpty()}") }
                    PrivilegedCommandResult.failure(error.message ?: error::class.java.simpleName)
                }
        }

    suspend fun testConnection(): PrivilegedCommandResult {
        val result = execute("id; getprop ro.build.version.release", 3_000L)
        mutableState.update {
            it.copy(detail = if (result.success) result.stdout.trim().lineSequence().firstOrNull().orEmpty() else result.error ?: "Connection test failed")
        }
        return result
    }

    fun isReady(): Boolean = enabled && service != null && mutableState.value.connected

    private fun refreshState(detail: String? = null) {
        if (!initialized) return
        val binderAvailable = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        val permissionGranted = binderAvailable && runCatching {
            !Shizuku.isPreV11() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        val uid = if (binderAvailable) runCatching { Shizuku.getUid() }.getOrNull() else null
        val version = if (binderAvailable) runCatching { Shizuku.getVersion() }.getOrNull() else null
        mutableState.update {
            it.copy(
                enabled = enabled,
                binderAvailable = binderAvailable,
                permissionGranted = permissionGranted,
                serverUid = uid,
                serverVersion = version,
                detail = detail ?: when {
                    !enabled -> "Privileged backend disabled"
                    !binderAvailable -> "Install and start Shizuku"
                    !permissionGranted -> "Shizuku permission is required"
                    service != null -> it.detail
                    else -> "Ready to connect privileged service"
                },
            )
        }
    }

    private fun userServiceArgs(): Shizuku.UserServiceArgs = Shizuku.UserServiceArgs(
        ComponentName(applicationContext.packageName, MusePrivilegedService::class.java.name),
    )
        .daemon(false)
        .processNameSuffix("privileged")
        .debuggable(BuildConfig.DEBUG)
        .version(BuildConfig.VERSION_CODE)
}
