package com.androidagent.app.accessibility

import android.content.Context
import android.util.Log
import com.androidagent.app.agent.ActorOverlayThought
import com.androidagent.app.agent.AgentRuntime
import com.androidagent.app.agent.AgentUiState
import com.androidagent.app.agent.DEVICE_ACTION_TURN_LIMIT
import com.androidagent.app.agent.RuntimeOutcome
import com.androidagent.app.agent.RuntimeResult
import com.androidagent.app.agent.SensitiveOperationPolicy
import com.androidagent.app.data.SecureSettings
import com.androidagent.app.privileged.PrivilegedBackendRouter
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
    private val runResults = RunResultCoordinator()

    val state: StateFlow<AgentUiState> = mutableState.asStateFlow()

    @Volatile
    private var runJob: Job? = null
    @Volatile
    private var activeRunId: String? = null
    @Volatile
    private var runGeneration: Long = 0
    @Volatile
    private var paused: Boolean = false
    private val pendingDirectives = ArrayDeque<String>()

    fun setAccessibilityConnected(connected: Boolean) = update { copy(accessibilityConnected = connected) }
    fun setCurrentPackage(packageName: String) = update { copy(currentPackage = packageName) }

    /** Includes a cancelling job until its finally block releases the run slot. */
    fun isRunning(runId: String): Boolean = activeRunId == runId

    fun resultForRun(runId: String): RuntimeResult? = runResults.resultForRun(runId)

    /** Atomically consumes a completed run result for one caller (for example WorkManager). */
    fun consumeResult(runId: String): RuntimeResult? = runResults.consumeResult(runId)

    fun removeResult(runId: String) = runResults.removeResult(runId)

    fun stopCauseFor(runId: String): AgentStopCause? = runResults.stopCauseFor(runId)

    suspend fun awaitAndConsumeResult(runId: String, timeoutMillis: Long): RuntimeResult? =
        runResults.awaitAndConsumeResult(runId, timeoutMillis)

    fun registerLateResultTombstone(runId: String) = runResults.registerLateResultTombstone(runId)

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
            update { copy(status = "Failed", outcome = result.reason, goal = effectiveGoal) }
            log("Invalid task goal or missing API key")
            return AgentStartResult.InvalidGoal
        }
        val safetyFailure = SensitiveOperationPolicy.validateGoal(effectiveGoal).exceptionOrNull()
        if (safetyFailure != null) {
            val result = RuntimeResult.failure(RuntimeOutcome.SAFETY_BLOCKED, "SAFETY_BLOCKED: ${safetyFailure.message.orEmpty()}")
            update { copy(status = "Blocked", outcome = result.reason, goal = effectiveGoal) }
            log("Safety policy blocked the task goal")
            return AgentStartResult.SafetyBlocked(result.reason)
        }

        val service = AgentAccessibilityService.current()
            ?: run {
                val result = RuntimeResult.failure(RuntimeOutcome.ACCESSIBILITY_DISCONNECTED, "Accessibility service is not connected")
                update { copy(status = "Failed", outcome = result.reason, accessibilityConnected = false, goal = effectiveGoal) }
                log(result.reason)
                return AgentStartResult.AccessibilityDisconnected
            }

        val generation = ++runGeneration
        val runId = UUID.randomUUID().toString()
        activeRunId = runId
        runResults.registerRun(runId)
        update {
            copy(
                running = false,
                step = 0,
                maxSteps = DEVICE_ACTION_TURN_LIMIT,
                status = "Preparing",
                goal = effectiveGoal,
                currentAction = "",
                progressSummaries = listOf(progressForPhase("Preparing")),
                outcome = "",
                logs = emptyList(),
            )
        }

        val applicationContext = context.applicationContext
        PrivilegedBackendRouter.configure(applicationContext, settings.privilegedBackendEnabled)
        AgentForegroundService.start(applicationContext)

        val job = scope.launch(start = CoroutineStart.LAZY) {
            updateFor(generation) {
                copy(running = true, step = 0, status = "Compiling")
                    .withProgress(progressForPhase("Compiling"))
            }
            try {
                val result = AgentRuntime(
                    context = context.applicationContext,
                    settings = settings,
                    service = service,
                    onPhase = { _, phase ->
                        updateFor(generation) {
                            copy(status = phase).withProgress(progressForPhase(phase))
                        }
                    },
                    onThought = { lines ->
                        updateFor(generation) {
                            copy(thoughtLines = lines.takeLast(ActorOverlayThought.MAX_STORED_LINES))
                        }
                    },
                    onLog = { message ->
                        logFor(generation, message)
                        progressForLog(message)?.let { progress ->
                            updateFor(generation) { withProgress(progress) }
                        }
                    },
                    onAction = { action ->
                        updateFor(generation) {
                            copy(currentAction = action).withProgress(progressForAction(action))
                        }
                    },
                    onActionCount = { count ->
                        updateFor(generation) { copy(step = count.coerceAtMost(maxSteps)) }
                    },
                    isPaused = { paused },
                    pollDirectives = { drainDirectives() },
                    goalOverride = goalOverride,
                    runIdOverride = runId,
                    cancellationOutcomeProvider = { runResults.stopCauseFor(runId)?.runtimeOutcome() },
                ).run()
                storeResult(runId, result)
                if (result.succeeded) {
                    logFor(generation, "Verified completion: ${result.reason}")
                    updateFor(generation) {
                        copy(status = "Succeeded: ${result.reason}", outcome = result.reason)
                            .withProgress("✓ 任务完成证据已验证")
                    }
                } else {
                    logFor(generation, "Failed: ${result.reason}")
                    updateFor(generation) {
                        copy(status = "Failed", outcome = result.reason)
                            .withProgress("! 任务停止：${userFacingFailure(result.reason)}")
                    }
                }
            } catch (_: CancellationException) {
                val cause = runResults.stopCauseFor(runId) ?: AgentStopCause.APP_SHUTDOWN
                val outcome = cause.runtimeOutcome()
                val reason = cause.reason()
                storeResult(runId, RuntimeResult.failure(outcome, reason, runId))
                updateFor(generation) { copy(status = if (outcome == RuntimeOutcome.TIMEOUT) "Timed out" else "Cancelled", outcome = reason) }
            } catch (error: Throwable) {
                val result = RuntimeResult.failure(RuntimeOutcome.INTERNAL_ERROR, error.message ?: error::class.simpleName.orEmpty(), runId)
                storeResult(runId, result)
                Log.e(TAG, "Agent runtime failed", error)
                logFor(generation, "Failed: ${error.message ?: error::class.simpleName}")
                updateFor(generation) { copy(status = "Failed", outcome = result.reason) }
            } finally {
                completeRun(generation, runId, coroutineContext[Job])
            }
        }
        paused = false
        pendingDirectives.clear()
        runJob = job
        job.start()
        return AgentStartResult.Started(runId)
    }

    @Synchronized
    fun stop() {
        paused = false
        pendingDirectives.clear()
        cancelRun(AgentStopCause.USER_REQUEST, null, "Stopped by user")
    }

    @Synchronized
    fun togglePause(): Boolean {
        if (runJob == null) return false
        paused = !paused
        update { copy(paused = paused, status = if (paused) "Paused" else "Observing") }
        log(if (paused) "Paused by user" else "Resumed by user")
        return paused
    }

    @Synchronized
    fun skipCurrentApproach() {
        if (runJob == null) return
        pendingDirectives.addLast(
            "USER_SKIP: abandon the current approach and take a different live action. Do not repeat the last query or click.",
        )
        if (paused) {
            paused = false
            update { copy(paused = false, status = "Observing") }
        }
        log("User skipped the current approach")
    }

    @Synchronized
    private fun drainDirectives(): List<String> {
        if (pendingDirectives.isEmpty()) return emptyList()
        val notes = pendingDirectives.toList()
        pendingDirectives.clear()
        return notes
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
        val existingCause = currentId?.let(runResults::stopCauseFor)
        val effectiveCause = currentId?.let { runResults.recordStopCause(it, cause) } ?: cause
        val effectiveReason = if (existingCause == null) reason else effectiveCause.reason()
        if (existingCause == null) runGeneration += 1
        job.cancel(CancellationException(effectiveReason))
        update { copy(running = job.isActive, status = "Stopping", currentAction = "", outcome = effectiveReason) }
        log(effectiveReason)
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
        paused = false
        pendingDirectives.clear()
        if (generation == runGeneration) {
            update { copy(running = false, currentAction = "", paused = false) }
        } else {
            update { copy(running = false, currentAction = "", status = "Stopped", paused = false) }
        }
    }

    private fun storeResult(runId: String, result: RuntimeResult) {
        val normalized = if (result.runId == runId) result else result.copy(runId = runId)
        runResults.storeResult(runId, normalized)
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

private fun AgentUiState.withProgress(message: String): AgentUiState {
    val clean = message.trim().replace(Regex("\\s+"), " ").take(96)
    if (clean.isBlank() || progressSummaries.lastOrNull() == clean) return this
    return copy(progressSummaries = (progressSummaries + clean).takeLast(2))
}

private fun progressForPhase(phase: String): String = when (phase) {
    "Preparing" -> "· 正在准备任务环境"
    "Compiling" -> "· 正在快速拆解目标"
    "Observing" -> "· 正在读取当前页面"
    "Planning" -> "· 正在选择下一步工具"
    "Acting" -> "→ 正在执行已确认的操作"
    "Critiquing" -> "· 正在检查页面变化"
    "Verifying" -> "· 正在核验任务结果"
    "Replanning" -> "↻ 当前路径受阻，正在切换策略"
    else -> phase.substringBefore(':').take(64)
}

private fun progressForAction(action: String): String = when {
    action.isBlank() -> ""
    action.startsWith("terminal(") -> "→ Shizuku 正在执行终端操作"
    action.startsWith("launch_app(") -> "→ 正在启动目标应用"
    action.startsWith("click_") || action.startsWith("tap_point(") -> "→ 无障碍已定位目标，正在点击"
    action.startsWith("input_text(") -> "→ 无障碍正在填写目标内容"
    action.startsWith("submit_input(") -> "→ 无障碍正在提交已核对的内容"
    action.startsWith("swipe(") -> "→ 无障碍正在浏览页面中的更多内容"
    action.startsWith("ensure_toggle(") -> "→ 无障碍正在核对并调整开关状态"
    action.startsWith("bind_predicate(") -> "· 正在绑定可验证的页面目标"
    action.startsWith("wait(") -> "· 正在等待页面稳定"
    action == "back" -> "→ 无障碍正在返回上一层"
    action == "home" -> "→ 无障碍正在返回桌面"
    action == "finish" -> "· 证据已齐，正在完成验收"
    else -> "→ 正在执行下一步操作"
}

private fun progressForLog(message: String): String? = when {
    message.startsWith("Plan ready:") -> "✓ 已生成可验证执行计划"
    message.startsWith("Observation replan ready:") -> "↻ 已根据当前页面更新路径"
    message.startsWith("Milestone ") && message.endsWith(" verified locally") -> "✓ 已验证当前子目标"
    message.startsWith("Completion not yet proven:") -> "· 完成证据不足，继续执行"
    message.startsWith("planner error:") -> "! 模型响应异常，正在恢复"
    message.startsWith("Result:") -> "✓ 已执行操作并检查设备状态"
    message.startsWith("Settle timeout;") -> "· 页面状态未稳定，正在重新观察"
    else -> null
}

private fun userFacingFailure(reason: String): String = when {
    reason.contains("device-action budget exhausted", ignoreCase = true) -> "50 次真实动作预算已用完"
    reason.contains("control-cycle budget exhausted", ignoreCase = true) -> "内部观察循环已达到保护上限"
    reason.contains("network", ignoreCase = true) || reason.contains("HTTP", ignoreCase = true) -> "模型网络异常"
    reason.contains("accessibility", ignoreCase = true) -> "无障碍服务已断开"
    else -> "未取得可验证的完成证据"
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
