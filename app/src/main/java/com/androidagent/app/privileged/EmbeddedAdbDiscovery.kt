package com.androidagent.app.privileged

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.ArrayDeque

internal data class EmbeddedAdbDiscoveryState(
    val running: Boolean = false,
    val pairEndpoints: List<AdbEndpoint> = emptyList(),
    val connectEndpoints: List<AdbEndpoint> = emptyList(),
)

/**
 * Small Android NSD adapter for the two wireless-debugging service types.
 * Resolution is serialized because older NsdManager implementations reject
 * concurrent resolveService calls.
 */
internal class EmbeddedAdbDiscovery(context: Context) : AutoCloseable {
    private val nsdManager = context.applicationContext.getSystemService(NsdManager::class.java)
    private val mutableState = MutableStateFlow(EmbeddedAdbDiscoveryState())
    val state: StateFlow<EmbeddedAdbDiscoveryState> = mutableState.asStateFlow()

    private val resolutionQueue = ArrayDeque<PendingResolution>()
    private var resolving = false
    private var running = false

    private val pairingListener = listener(ServiceKind.PAIR)
    private val connectionListener = listener(ServiceKind.CONNECT)

    @Synchronized
    fun start() {
        if (running) return
        running = true
        mutableState.value = EmbeddedAdbDiscoveryState(running = true)
        discover(PAIRING_SERVICE_TYPE, pairingListener)
        discover(CONNECTION_SERVICE_TYPE, connectionListener)
    }

    @Synchronized
    fun stop() {
        if (!running) return
        running = false
        resolutionQueue.clear()
        resolving = false
        runCatching { nsdManager.stopServiceDiscovery(pairingListener) }
        runCatching { nsdManager.stopServiceDiscovery(connectionListener) }
        mutableState.update { it.copy(running = false) }
    }

    override fun close() = stop()

    private fun discover(type: String, listener: NsdManager.DiscoveryListener) {
        runCatching {
            nsdManager.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener)
        }.onFailure {
            synchronized(this) {
                mutableState.update { state -> state.copy(running = false) }
            }
        }
    }

    private fun listener(kind: ServiceKind) = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) = Unit

        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            enqueue(serviceInfo, kind)
        }

        override fun onServiceLost(serviceInfo: NsdServiceInfo) {
            // NsdServiceInfo does not include the resolved host and port here.
            // A new discovery pass replaces stale endpoint entries.
        }

        override fun onDiscoveryStopped(serviceType: String) = Unit
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = stop()
        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
    }

    @Synchronized
    private fun enqueue(serviceInfo: NsdServiceInfo, kind: ServiceKind) {
        if (!running) return
        resolutionQueue += PendingResolution(serviceInfo, kind)
        resolveNext()
    }

    @Synchronized
    private fun resolveNext() {
        if (!running || resolving) return
        val pending = if (resolutionQueue.isEmpty()) null else resolutionQueue.removeFirst()
        if (pending == null) return
        resolving = true
        @Suppress("DEPRECATION")
        nsdManager.resolveService(pending.info, object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                finishResolution()
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                val host = serviceInfo.host?.hostAddress.orEmpty()
                val port = serviceInfo.port
                if (host.isNotBlank() && port in 1..65_535) {
                    val endpoint = AdbEndpoint(host, port)
                    synchronized(this@EmbeddedAdbDiscovery) {
                        mutableState.update { state ->
                            when (pending.kind) {
                                ServiceKind.PAIR -> state.copy(pairEndpoints = (state.pairEndpoints + endpoint).distinct())
                                ServiceKind.CONNECT -> state.copy(connectEndpoints = (state.connectEndpoints + endpoint).distinct())
                            }
                        }
                    }
                }
                finishResolution()
            }
        })
    }

    @Synchronized
    private fun finishResolution() {
        resolving = false
        resolveNext()
    }

    private data class PendingResolution(
        val info: NsdServiceInfo,
        val kind: ServiceKind,
    )

    private enum class ServiceKind { PAIR, CONNECT }

    private companion object {
        const val PAIRING_SERVICE_TYPE = "_adb-tls-pairing._tcp."
        const val CONNECTION_SERVICE_TYPE = "_adb-tls-connect._tcp."
    }
}
