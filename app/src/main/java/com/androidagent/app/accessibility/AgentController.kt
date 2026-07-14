package com.androidagent.app.accessibility

import android.content.Context
import android.util.Log
import com.androidagent.app.agent.AgentRuntime
import com.androidagent.app.agent.AgentUiState
import com.androidagent.app.data.SecureSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

object AgentController {
    private const val TAG = "AndroidAgent"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutableState = MutableStateFlow(AgentUiState())
    val state: StateFlow<AgentUiState> = mutableState.asStateFlow()
    @Volatile
    private var runJob: Job? = null
    @Volatile
    private var runGeneration: Long = 0

    fun setAccessibilityConnected(connected: Boolean) = update { copy(accessibilityConnected = connected) }
    fun setCurrentPackage(packageName: String) = update { copy(currentPackage = packageName) }

    @Synchronized
    fun start(context: Context, settings: SecureSettings) {
        if (runJob?.isActive == true) return
        val generation = ++runGeneration
        update {
            copy(
                step = 0,
                maxSteps = 24,
                status = "Preparing",
                goal = settings.taskGoal,
                currentAction = "",
                outcome = "",
                logs = emptyList(),
            )
        }
        if (settings.apiKey.isBlank() || settings.taskGoal.isBlank()) {
            log("API key and task are required")
            updateFor(generation) { copy(status = "Failed", outcome = "API key and task are required") }
            return
        }
        val service = AgentAccessibilityService.current()
        if (service == null) {
            log("Accessibility service is not connected")
            updateFor(generation) {
                copy(status = "Failed", outcome = "Accessibility service is not connected", accessibilityConnected = false)
            }
            return
        }

        val job = scope.launch(start = CoroutineStart.LAZY) {
            updateFor(generation) { copy(running = true, step = 0, status = "Compiling") }
            try {
                val result = AgentRuntime(
                    context = context.applicationContext,
                    settings = settings,
                    service = service,
                    onPhase = { step, phase -> updateFor(generation) { copy(step = step, status = phase) } },
                    onLog = { message -> logFor(generation, message) },
                    onAction = { action -> updateFor(generation) { copy(currentAction = action) } },
                ).run()
                if (result.succeeded) {
                    logFor(generation, "Verified completion: ${result.reason}")
                    updateFor(generation) { copy(status = "Succeeded: ${result.reason}", outcome = result.reason) }
                } else {
                    logFor(generation, "Failed: ${result.reason}")
                    updateFor(generation) { copy(status = "Failed", outcome = result.reason) }
                }
            } catch (_: CancellationException) {
                updateFor(generation) { copy(status = "Cancelled", outcome = "Run cancelled") }
            } catch (error: Throwable) {
                Log.e(TAG, "Agent runtime failed", error)
                logFor(generation, "Failed: ${error.message ?: error::class.simpleName}")
                updateFor(generation) {
                    copy(status = "Failed", outcome = error.message ?: error::class.simpleName.orEmpty())
                }
            } finally {
                completeRun(generation, coroutineContext[Job])
            }
        }
        runJob = job
        job.start()
    }

    @Synchronized
    fun stop() {
        cancelRun("Stopped by user")
    }

    @Synchronized
    fun stopWithReason(reason: String) {
        cancelRun(reason)
    }

    private fun cancelRun(reason: String) {
        runGeneration += 1
        val job = runJob
        runJob = null
        job?.cancel(CancellationException(reason))
        update { copy(running = false, status = "Stopped", currentAction = "", outcome = reason) }
        log(reason)
    }

    @Synchronized
    private fun completeRun(generation: Long, completedJob: Job?) {
        if (generation != runGeneration || runJob !== completedJob) return
        runJob = null
        update { copy(running = false, currentAction = "") }
    }

    private fun log(message: String) {
        Log.i(TAG, message)
        update { copy(logs = (listOf(message) + logs).take(80)) }
    }

    private fun logFor(generation: Long, message: String) {
        Log.i(TAG, message)
        updateFor(generation) { copy(logs = (listOf(message) + logs).take(80)) }
    }

    private inline fun update(block: AgentUiState.() -> AgentUiState) {
        mutableState.update { state -> state.block() }
    }

    private inline fun updateFor(generation: Long, block: AgentUiState.() -> AgentUiState) {
        mutableState.update { state ->
            if (generation == runGeneration) state.block() else state
        }
    }
}
