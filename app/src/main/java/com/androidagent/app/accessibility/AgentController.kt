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

enum class AgentStopCause {
    USER_REQUEST,
    ACCESSIBILITY_INTERRUPTED,
    ACCESSIBILITY_DISCONNECTED,
    WORKER_TIMEOUT,
    APP_SHUTDOWN,
}

sealed interface AgentStartResult {
    data class Started(val runId: String) : AgentStartResult
    data class Busy(val activeRunId: String) : AgentStartResult
    data object InvalidGoal : AgentStartResult
    data class SafetyBlocked(val reason: String) : AgentStartResult
    data object AccessibilityDisconnected : AgentStartResult
}

object AgentController {
    private const val TAG = "AndroidAgent"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutableState = MutableStateFlow(AgentUiState())
    private val results = ConcurrentHashMap<String, RuntimeResult>()
    private val stopCauses = ConcurrentHashMap<String, AgentStopCause>()
    private val resultOrder = ArrayDeque<String>()

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

    /** Atomically consumes a completed run result for one caller (for example WorkManager). */
    @Synchronized
    fun consumeResult(runId: String): RuntimeResult? = results.remove(runId).also {
        resultOrder.remove(runId)
        stopCauses.remove(runId)
    }

    @Synchronized
    fun removeResult(runId: String) {
        results.remove(runId)
        resultOrder.remove(runId)
        stopCauses.remove(runId)
    }

    fun stopCauseFor(runId: String): AgentStopCause? = stopCauses[runId]

    fun currentRunId(): String? = activeRunId

    @Synchronized
    fun start(context: Context, settings: SecureSettings, goalOverride: String? = null): AgentStartResult {
        val busyId = activeRunId
        // A cancelled coroutine may still be unwinding. Keep the run slot
        // occupied until its finally block clears the matching runId, so a
        // replacement cannot touch AccessibilityService concurrently.
        if (!busyId.isNullOrBlank()) return AgentStartResult.Busy(busyId)

        val effectiveGoal = goalOverride?.trim().takeUnless { it.isNullOrBlank() } ?: settings.taskGoal
        if (settings.apiKey.isBlank() || effectiveGoal.isBlank()) {
            val result = RuntimeResult.failure(RuntimeOutcome.PERMANENT_PLAN_ERROR, "API key and a permitted task goal are required")
            lastResult = result
            update { copy(status = "Failed", outcome = result.reason, goal = effectiveGoal) }
            log("Invalid task goal or missing API key")
            return AgentStartResult.InvalidGoal
        }
        val safetyFailure = SensitiveOperationPolicy.validateGoal(effectiveGoal).exceptionOrNull()
        if (safetyFailure != null) {
            val result = RuntimeResult.failure(RuntimeOutcome.SAFETY_BLOCKED, "SAFETY_BLOCKED: ${safetyFailure.message.orEmpty()}")
            lastResult = result
            update { copy(status = "Blocked", outcome = result.reason, goal = effectiveGoal) }
            log("Safety policy blocked the task goal")
            return AgentStartResult.SafetyBlocked(result.reason)
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
                    cancellationOutcomeProvider = { stopCauses[runId]?.runtimeOutcome() },
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
                val cause = stopCauses[runId] ?: AgentStopCause.APP_SHUTDOWN
                val outcome = cause.runtimeOutcome()
                val reason = cause.reason()
                storeResult(generation, runId, RuntimeResult.failure(outcome, reason, runId))
                updateFor(generation) { copy(status = if (outcome == RuntimeOutcome.TIMEOUT) "Timed out" else "Cancelled", outcome = reason) }
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
        cancelRun(AgentStopCause.USER_REQUEST, null, "Stopped by user")
    }

    @Synchronized
    fun stopWithReason(reason: String) {
        cancelRun(AgentStopCause.USER_REQUEST, null, reason)
    }

    @Synchronized
    fun stopWithReason(reason: String, runId: String): Boolean = cancelRun(reason, runId)

    fun stopWithCause(cause: AgentStopCause, runId: String? = null, reason: String = cause.reason()): Boolean =
        cancelRun(cause, runId, reason)

    @Synchronized
    fun cancelRun(cause: AgentStopCause, requestedRunId: String? = null, reason: String = cause.reason()): Boolean {
        val currentId = activeRunId
        if (requestedRunId != null && requestedRunId != currentId) return false
        val job = runJob
        if (job == null) return false
        currentId?.let { stopCauses[it] = cause }
        runGeneration += 1
        job?.cancel(CancellationException(reason))
        update { copy(running = job.isActive, status = "Stopping", currentAction = "", outcome = reason) }
        log(reason)
        return true
    }

    @Synchronized
    private fun cancelRun(reason: String, requestedRunId: String?): Boolean =
        cancelRun(AgentStopCause.USER_REQUEST, requestedRunId, reason)

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
        synchronized(this) {
            resultOrder.remove(runId)
            resultOrder.addLast(runId)
            while (resultOrder.size > MAX_RETAINED_RESULTS) {
                val evicted = resultOrder.removeFirst()
                results.remove(evicted)
                stopCauses.remove(evicted)
            }
        }
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

    private const val MAX_RETAINED_RESULTS = 20
}

object AgentStopCausePolicy {
    fun outcome(cause: AgentStopCause): RuntimeOutcome = when (cause) {
        AgentStopCause.USER_REQUEST -> RuntimeOutcome.USER_CANCELLED
        AgentStopCause.ACCESSIBILITY_INTERRUPTED,
        AgentStopCause.ACCESSIBILITY_DISCONNECTED,
        -> RuntimeOutcome.ACCESSIBILITY_DISCONNECTED
        AgentStopCause.WORKER_TIMEOUT -> RuntimeOutcome.TIMEOUT
        AgentStopCause.APP_SHUTDOWN -> RuntimeOutcome.USER_CANCELLED
    }

    fun reason(cause: AgentStopCause): String = when (cause) {
        AgentStopCause.USER_REQUEST -> "Run cancelled by user"
        AgentStopCause.ACCESSIBILITY_INTERRUPTED -> "Accessibility service interrupted"
        AgentStopCause.ACCESSIBILITY_DISCONNECTED -> "Accessibility service disconnected"
        AgentStopCause.WORKER_TIMEOUT -> "Scheduled run exceeded its worker deadline"
        AgentStopCause.APP_SHUTDOWN -> "Application shutdown cancelled the run"
    }
}

private fun AgentStopCause.runtimeOutcome(): RuntimeOutcome = AgentStopCausePolicy.outcome(this)
private fun AgentStopCause.reason(): String = AgentStopCausePolicy.reason(this)
