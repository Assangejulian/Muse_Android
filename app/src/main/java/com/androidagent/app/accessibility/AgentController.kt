package com.androidagent.app.accessibility

import android.content.Context
import android.util.Log
import com.androidagent.app.agent.AgentRuntime
import com.androidagent.app.agent.AgentUiState
import com.androidagent.app.agent.RuntimeOutcome
import com.androidagent.app.agent.RuntimeResult
import com.androidagent.app.agent.SensitiveOperationPolicy
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
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

sealed interface AgentStartResult {
    data class Started(val runId: String) : AgentStartResult
    data class Busy(val activeRunId: String) : AgentStartResult
    data object InvalidGoal : AgentStartResult
    data object AccessibilityDisconnected : AgentStartResult
}

object AgentController {
    private const val TAG = "AndroidAgent"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutableState = MutableStateFlow(AgentUiState())
    private val results = ConcurrentHashMap<String, RuntimeResult>()

    val state: StateFlow<AgentUiState> = mutableState.asStateFlow()

    @Volatile
    private var runJob: Job? = null
    @Volatile
    private var activeRunId: String? = null
    @Volatile
    private var runGeneration: Long = 0

    /** Compatibility view for the UI. Workers must use resultForRun(runId). */
    @Deprecated("Use resultForRun(runId) to avoid cross-run races")
    @Volatile
    var lastResult: RuntimeResult? = null
        private set

    fun setAccessibilityConnected(connected: Boolean) = update { copy(accessibilityConnected = connected) }
    fun setCurrentPackage(packageName: String) = update { copy(currentPackage = packageName) }

    /** Includes a cancelling job until its finally block releases the run slot. */
    fun isRunning(runId: String): Boolean = activeRunId == runId

    fun resultForRun(runId: String): RuntimeResult? = results[runId]

    fun currentRunId(): String? = activeRunId

    @Synchronized
    fun start(context: Context, settings: SecureSettings, goalOverride: String? = null): AgentStartResult {
        val busyId = activeRunId
        // A cancelled coroutine may still be unwinding. Keep the run slot
        // occupied until its finally block clears the matching runId, so a
        // replacement cannot touch AccessibilityService concurrently.
        if (!busyId.isNullOrBlank()) return AgentStartResult.Busy(busyId)

        val effectiveGoal = goalOverride?.trim().takeUnless { it.isNullOrBlank() } ?: settings.taskGoal
        if (settings.apiKey.isBlank() || effectiveGoal.isBlank() || SensitiveOperationPolicy.validateGoal(effectiveGoal).isFailure) {
            val result = RuntimeResult.failure(RuntimeOutcome.PERMANENT_PLAN_ERROR, "API key and a permitted task goal are required")
            lastResult = result
            update { copy(status = "Failed", outcome = result.reason, goal = effectiveGoal) }
            log("Invalid task goal or missing API key")
            return AgentStartResult.InvalidGoal
        }

        val service = AgentAccessibilityService.current()
            ?: run {
                val result = RuntimeResult.failure(RuntimeOutcome.ACCESSIBILITY_DISCONNECTED, "Accessibility service is not connected")
                lastResult = result
                update { copy(status = "Failed", outcome = result.reason, accessibilityConnected = false, goal = effectiveGoal) }
                log(result.reason)
                return AgentStartResult.AccessibilityDisconnected
            }

        val generation = ++runGeneration
        val runId = UUID.randomUUID().toString()
        activeRunId = runId
        lastResult = null
        update {
            copy(
                running = false,
                step = 0,
                maxSteps = 24,
                status = "Preparing",
                goal = effectiveGoal,
                currentAction = "",
                outcome = "",
                logs = emptyList(),
            )
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
                    goalOverride = goalOverride,
                    runIdOverride = runId,
                ).run()
                storeResult(generation, runId, result)
                if (result.succeeded) {
                    logFor(generation, "Verified completion: ${result.reason}")
                    updateFor(generation) { copy(status = "Succeeded: ${result.reason}", outcome = result.reason) }
                } else {
                    logFor(generation, "Failed: ${result.reason}")
                    updateFor(generation) { copy(status = "Failed", outcome = result.reason) }
                }
            } catch (_: CancellationException) {
                storeResult(generation, runId, RuntimeResult.failure(RuntimeOutcome.USER_CANCELLED, "Run cancelled", runId))
                updateFor(generation) { copy(status = "Cancelled", outcome = "Run cancelled") }
            } catch (error: Throwable) {
                val result = RuntimeResult.failure(RuntimeOutcome.INTERNAL_ERROR, error.message ?: error::class.simpleName.orEmpty(), runId)
                storeResult(generation, runId, result)
                Log.e(TAG, "Agent runtime failed", error)
                logFor(generation, "Failed: ${error.message ?: error::class.simpleName}")
                updateFor(generation) { copy(status = "Failed", outcome = result.reason) }
            } finally {
                completeRun(generation, runId, coroutineContext[Job])
            }
        }
        runJob = job
        job.start()
        return AgentStartResult.Started(runId)
    }

    @Synchronized
    fun stop() {
        cancelRun("Stopped by user", null)
    }

    @Synchronized
    fun stopWithReason(reason: String) {
        cancelRun(reason, null)
    }

    @Synchronized
    fun stopWithReason(reason: String, runId: String): Boolean = cancelRun(reason, runId)

    private fun cancelRun(reason: String, requestedRunId: String?): Boolean {
        val currentId = activeRunId
        if (requestedRunId != null && requestedRunId != currentId) return false
        runGeneration += 1
        val job = runJob
        if (job == null) return false
        job?.cancel(CancellationException(reason))
        update { copy(running = job.isActive, status = "Stopping", currentAction = "", outcome = reason) }
        log(reason)
        return true
    }

    @Synchronized
    private fun completeRun(generation: Long, runId: String, completedJob: Job?) {
        if (activeRunId != runId || runJob !== completedJob) return
        runJob = null
        activeRunId = null
        if (generation == runGeneration) {
            update { copy(running = false, currentAction = "") }
        } else {
            update { copy(running = false, currentAction = "", status = "Stopped") }
        }
    }

    private fun storeResult(generation: Long, runId: String, result: RuntimeResult) {
        val normalized = if (result.runId == runId) result else result.copy(runId = runId)
        results[runId] = normalized
        if (generation == runGeneration) lastResult = normalized
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
        mutableState.update { state -> if (generation == runGeneration) state.block() else state }
    }
}
