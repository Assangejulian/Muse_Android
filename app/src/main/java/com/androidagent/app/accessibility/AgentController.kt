package com.androidagent.app.accessibility

import android.content.Context
import android.util.Log
import com.androidagent.app.agent.AgentRuntime
import com.androidagent.app.agent.AgentUiState
import com.androidagent.app.data.SecureSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

object AgentController {
    private const val TAG = "AndroidAgent"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutableState = MutableStateFlow(AgentUiState())
    val state: StateFlow<AgentUiState> = mutableState.asStateFlow()
    private var runJob: Job? = null

    fun setAccessibilityConnected(connected: Boolean) = update { copy(accessibilityConnected = connected) }
    fun setCurrentPackage(packageName: String) = update { copy(currentPackage = packageName) }

    fun start(context: Context, settings: SecureSettings) {
        if (runJob?.isActive == true) return
        update { copy(step = 0, status = "Preparing", logs = emptyList()) }
        if (settings.apiKey.isBlank() || settings.taskGoal.isBlank()) {
            log("API key and task are required")
            update { copy(status = "Failed") }
            return
        }
        val service = AgentAccessibilityService.current()
        if (service == null) {
            log("Accessibility service is not connected")
            update { copy(status = "Failed", accessibilityConnected = false) }
            return
        }

        runJob = scope.launch {
            update { copy(running = true, step = 0, status = "Compiling") }
            try {
                val result = AgentRuntime(
                    context = context.applicationContext,
                    settings = settings,
                    service = service,
                    onPhase = { step, phase -> update { copy(step = step, status = phase) } },
                    onLog = ::log,
                ).run()
                if (result.succeeded) {
                    log("Verified completion: ${result.reason}")
                    update { copy(status = "Succeeded: ${result.reason}") }
                } else {
                    log("Failed: ${result.reason}")
                    update { copy(status = "Failed") }
                }
            } catch (_: CancellationException) {
                update { copy(status = "Cancelled") }
            } catch (error: Throwable) {
                Log.e(TAG, "Agent runtime failed", error)
                log("Failed: ${error.message ?: error::class.simpleName}")
                update { copy(status = "Failed") }
            } finally {
                update { copy(running = false) }
            }
        }
    }

    fun stop() {
        runJob?.cancel()
        runJob = null
        update { copy(running = false, status = "Stopped") }
        log("Stopped by user")
    }

    private fun log(message: String) {
        Log.i(TAG, message)
        update { copy(logs = (listOf(message) + logs).take(80)) }
    }

    private inline fun update(block: AgentUiState.() -> AgentUiState) {
        mutableState.value = mutableState.value.block()
    }
}
