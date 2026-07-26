package com.androidagent.app.privileged

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.flyfishxu.kadb.Kadb
import com.flyfishxu.kadb.cert.KadbCert
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

data class AdbEndpoint(
    val host: String,
    val port: Int,
) {
    val label: String get() = "$host:$port"
}

data class EmbeddedAdbState(
    val enabled: Boolean = false,
    val discovering: Boolean = false,
    val pairing: Boolean = false,
    val connecting: Boolean = false,
    val connected: Boolean = false,
    val paired: Boolean = false,
    val pairEndpoints: List<AdbEndpoint> = emptyList(),
    val connectEndpoints: List<AdbEndpoint> = emptyList(),
    val activeEndpoint: AdbEndpoint? = null,
    val suggestedHost: String = "",
    val identity: String = "",
    val detail: String = "Built-in ADB is disabled",
)

/**
 * Direct ADB client for Android wireless debugging.
 *
 * The host key stays in Muse's private data directory. Commands are executed
 * by adbd as the shell user; no separate Shizuku manager app is required.
 */
object EmbeddedAdbBridge {
    private const val PREFS = "embedded_adb"
    private const val KEY_PAIRED = "paired"
    private const val KEY_HOST = "connect_host"
    private const val KEY_PORT = "connect_port"
    private const val CONNECT_TIMEOUT_MILLIS = 6_000
    private const val SOCKET_TIMEOUT_MILLIS = 30_000

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connectionMutex = Mutex()
    private val mutableState = MutableStateFlow(EmbeddedAdbState())
    val state: StateFlow<EmbeddedAdbState> = mutableState.asStateFlow()

    @Volatile
    private var initialized = false

    @Volatile
    private var enabled = false

    private lateinit var applicationContext: Context
    private lateinit var discovery: EmbeddedAdbDiscovery
    private var connection: Kadb? = null

    @Synchronized
    fun initialize(context: Context) {
        if (initialized) return
        applicationContext = context.applicationContext
        configureHostIdentity()
        discovery = EmbeddedAdbDiscovery(applicationContext)
        initialized = true
        val prefs = prefs()
        mutableState.update {
            it.copy(
                paired = prefs.getBoolean(KEY_PAIRED, false),
                detail = "Built-in ADB is ready to discover wireless debugging",
            )
        }
        scope.launch {
            discovery.state.collectLatest { discovered ->
                mutableState.update {
                    it.copy(
                        discovering = discovered.running,
                        pairEndpoints = discovered.pairEndpoints,
                        connectEndpoints = discovered.connectEndpoints,
                        suggestedHost = discovered.pairEndpoints.firstOrNull()?.host
                            ?: discovered.connectEndpoints.firstOrNull()?.host
                            ?: currentWifiIpv4().orEmpty(),
                    )
                }
            }
        }
    }

    fun configure(context: Context, shouldEnable: Boolean) {
        initialize(context)
        enabled = shouldEnable
        mutableState.update { it.copy(enabled = shouldEnable) }
        if (!shouldEnable) {
            disconnect("Built-in ADB is disabled")
            discovery.stop()
            return
        }
        refreshDiscovery()
        val saved = savedEndpoint()
        if (saved != null && !mutableState.value.connected && !mutableState.value.connecting) {
            scope.launch { connect(saved) }
        }
    }

    fun refreshDiscovery() {
        if (!initialized || !enabled) return
        discovery.stop()
        discovery.start()
        mutableState.update {
            it.copy(
                discovering = true,
                suggestedHost = currentWifiIpv4().orEmpty().ifBlank { it.suggestedHost },
                detail = "Discovering wireless debugging endpoints",
            )
        }
    }

    suspend fun pair(
        pairingCode: String,
        manualHost: String? = null,
        manualPairPort: Int? = null,
        manualConnectPort: Int? = null,
    ): PrivilegedCommandResult {
        if (!initialized || !enabled) return PrivilegedCommandResult.failure("Built-in ADB is disabled")
        val code = pairingCode.trim()
        if (!code.matches(Regex("\\d{6}"))) return PrivilegedCommandResult.failure("Pairing code must contain 6 digits")
        val endpoint = EmbeddedAdbEndpointPolicy.pairEndpoint(
            discovered = mutableState.value.pairEndpoints,
            manualHost = manualHost,
            manualPort = manualPairPort,
            localWifiHost = currentWifiIpv4(),
        ) ?: return PrivilegedCommandResult.failure(
            "No pairing endpoint found; enter the WLAN host and pairing port shown by Android",
        )

        mutableState.update { it.copy(pairing = true, detail = "Pairing with ${endpoint.label}") }
        return runCatching {
            withTimeout(30_000L) {
                Kadb.pair(endpoint.host, endpoint.port, code, "Muse")
            }
            prefs().edit().putBoolean(KEY_PAIRED, true).apply()
            mutableState.update { it.copy(pairing = false, paired = true, detail = "Pairing succeeded; discovering ADB connection") }
            refreshDiscovery()
            val explicitConnectEndpoint = EmbeddedAdbEndpointPolicy.connectEndpoint(
                discovered = mutableState.value.connectEndpoints,
                manualHost = manualHost,
                manualPort = manualConnectPort,
                localWifiHost = currentWifiIpv4(),
            )
            val connectEndpoint = explicitConnectEndpoint ?: withTimeoutOrNull(15_000L) {
                discovery.state.first { it.connectEndpoints.isNotEmpty() }.connectEndpoints.first()
            }
            if (connectEndpoint != null) {
                connect(connectEndpoint)
            } else {
                PrivilegedCommandResult(
                    exitCode = 0,
                    stdout = "Pairing succeeded. Keep wireless debugging enabled, then tap Reconnect.",
                    stderr = "",
                    timedOut = false,
                    durationMillis = 0,
                    uid = null,
                )
            }
        }.getOrElse { error ->
            mutableState.update { it.copy(pairing = false, detail = "Pairing failed: ${error.message.orEmpty()}") }
            PrivilegedCommandResult.failure(error.message ?: error::class.java.simpleName)
        }
    }

    suspend fun connect(endpoint: AdbEndpoint? = null): PrivilegedCommandResult {
        if (!initialized || !enabled) return PrivilegedCommandResult.failure("Built-in ADB is disabled")
        val target = endpoint
            ?: mutableState.value.connectEndpoints.firstOrNull()
            ?: savedEndpoint()
            ?: return PrivilegedCommandResult.failure("No ADB connection endpoint found")

        mutableState.update { it.copy(connecting = true, detail = "Connecting to ${target.label}") }
        return connectionMutex.withLock {
            var candidate: Kadb? = null
            runCatching {
                connection?.close()
                candidate = Kadb.create(
                    host = target.host,
                    port = target.port,
                    connectTimeout = CONNECT_TIMEOUT_MILLIS,
                    socketTimeout = SOCKET_TIMEOUT_MILLIS,
                )
                val response = runInterruptible(Dispatchers.IO) { candidate!!.shell("id") }
                check(response.exitCode == 0) { response.allOutput.ifBlank { "ADB identity check failed" } }
                connection = candidate
                candidate = null
                prefs().edit()
                    .putString(KEY_HOST, target.host)
                    .putInt(KEY_PORT, target.port)
                    .putBoolean(KEY_PAIRED, true)
                    .apply()
                val identity = response.output.trim().ifBlank { response.allOutput.trim() }
                mutableState.update {
                    it.copy(
                        connecting = false,
                        connected = true,
                        paired = true,
                        activeEndpoint = target,
                        identity = identity,
                        detail = "Built-in ADB connected",
                    )
                }
                PrivilegedCommandResult(0, identity, response.errorOutput, false, 0, 2000)
            }.getOrElse { error ->
                candidate?.close()
                connection?.close()
                connection = null
                mutableState.update {
                    it.copy(
                        connecting = false,
                        connected = false,
                        activeEndpoint = null,
                        identity = "",
                        detail = "ADB connection failed: ${error.message.orEmpty()}",
                    )
                }
                PrivilegedCommandResult.failure(error.message ?: error::class.java.simpleName)
            }
        }
    }

    suspend fun connectManual(host: String?, port: Int?): PrivilegedCommandResult {
        val endpoint = EmbeddedAdbEndpointPolicy.connectEndpoint(
            discovered = mutableState.value.connectEndpoints,
            manualHost = host,
            manualPort = port,
            localWifiHost = currentWifiIpv4(),
        ) ?: return PrivilegedCommandResult.failure(
            "No ADB connection endpoint found; enter the WLAN host and connection port shown by Android",
        )
        return connect(endpoint)
    }

    suspend fun execute(command: String, timeoutMillis: Long = 5_000L): PrivilegedCommandResult {
        if (!isReady()) return PrivilegedCommandResult.failure("Built-in ADB is not connected")
        val normalized = command.trim()
        if (normalized.isBlank() || normalized.length > 16_000) {
            return PrivilegedCommandResult.failure("Command must contain 1..16000 characters")
        }
        val timeout = timeoutMillis.coerceIn(250L, 30_000L)
        val startedAt = android.os.SystemClock.elapsedRealtime()
        return connectionMutex.withLock {
            val current = connection ?: return@withLock PrivilegedCommandResult.failure("Built-in ADB is not connected")
            runCatching {
                val response = withTimeout(timeout) {
                    runInterruptible(Dispatchers.IO) { current.shell(normalized) }
                }
                PrivilegedCommandResult(
                    exitCode = response.exitCode,
                    stdout = response.output.take(64 * 1_024),
                    stderr = response.errorOutput.take(64 * 1_024),
                    timedOut = false,
                    durationMillis = android.os.SystemClock.elapsedRealtime() - startedAt,
                    uid = 2000,
                )
            }.getOrElse { error ->
                connection?.close()
                connection = null
                mutableState.update {
                    it.copy(connected = false, activeEndpoint = null, detail = "ADB command failed: ${error.message.orEmpty()}")
                }
                PrivilegedCommandResult.failure(error.message ?: error::class.java.simpleName)
            }
        }
    }

    suspend fun testConnection(): PrivilegedCommandResult =
        execute("id; getprop ro.build.version.release", 3_000L)

    fun isReady(): Boolean = enabled && mutableState.value.connected && connection != null

    fun openWirelessDebugging(context: Context): Boolean = runCatching {
        context.startActivity(
            Intent("android.settings.WIRELESS_DEBUGGING_SETTINGS")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        true
    }.getOrDefault(false)

    @Synchronized
    fun disconnect(detail: String = "Built-in ADB disconnected") {
        connection?.close()
        connection = null
        mutableState.update {
            it.copy(
                connecting = false,
                connected = false,
                activeEndpoint = null,
                identity = "",
                detail = detail,
            )
        }
    }

    private fun savedEndpoint(): AdbEndpoint? {
        val host = prefs().getString(KEY_HOST, null)?.takeIf(String::isNotBlank) ?: return null
        val port = prefs().getInt(KEY_PORT, 0).takeIf { it in 1..65_535 } ?: return null
        return AdbEndpoint(host, port)
    }

    private fun configureHostIdentity() {
        val directory = File(applicationContext.filesDir, "adb").apply { mkdirs() }
        val certificateFile = File(directory, "muse_adb_host_cert.pem")
        val keyFile = File(directory, "muse_adb_host_key.pem")
        if (certificateFile.isFile && keyFile.isFile) {
            runCatching { KadbCert.set(certificateFile.readBytes(), keyFile.readBytes()) }
                .onSuccess { return }
        }
        val (certificate, key) = KadbCert.get(
            cn = "Muse",
            ou = "Muse",
            o = "Muse Android Agent",
            l = "Local",
            st = "Local",
            c = "CN",
        )
        certificateFile.writeBytes(certificate)
        keyFile.writeBytes(key)
    }

    @Suppress("DEPRECATION")
    private fun currentWifiIpv4(): String? {
        val manager = applicationContext.getSystemService(ConnectivityManager::class.java)
        return manager.allNetworks.asSequence()
            .filter { network ->
                manager.getNetworkCapabilities(network)
                    ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            }
            .flatMap { network ->
                manager.getLinkProperties(network)?.linkAddresses.orEmpty().asSequence()
            }
            .map { it.address }
            .firstOrNull { address ->
                address.address.size == 4 && !address.isLoopbackAddress && !address.isAnyLocalAddress
            }
            ?.hostAddress
    }

    private fun prefs() = applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

internal object EmbeddedAdbEndpointPolicy {
    fun pairEndpoint(
        discovered: List<AdbEndpoint>,
        manualHost: String?,
        manualPort: Int?,
        localWifiHost: String?,
    ): AdbEndpoint? {
        return endpoint(discovered, manualHost, manualPort, localWifiHost)
    }

    fun connectEndpoint(
        discovered: List<AdbEndpoint>,
        manualHost: String?,
        manualPort: Int?,
        localWifiHost: String?,
    ): AdbEndpoint? {
        if (manualPort == null) return discovered.singleOrNull()
        return endpoint(discovered, manualHost, manualPort, localWifiHost)
    }

    private fun endpoint(
        discovered: List<AdbEndpoint>,
        manualHost: String?,
        manualPort: Int?,
        localWifiHost: String?,
    ): AdbEndpoint? {
        if (manualPort != null && manualPort !in 1..65_535) return null
        if (manualPort != null) {
            return validHost(manualHost)?.let { AdbEndpoint(it, manualPort) }
                ?: discovered.firstOrNull { it.port == manualPort }
                ?: validHost(localWifiHost)?.let { AdbEndpoint(it, manualPort) }
        }
        return discovered.singleOrNull()
    }

    private fun validHost(value: String?): String? = value
        ?.trim()
        ?.takeIf { it.isNotBlank() && it.length <= 253 && it.none(Char::isWhitespace) }
}
