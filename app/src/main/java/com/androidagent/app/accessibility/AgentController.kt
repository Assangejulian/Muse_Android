package com.androidagent.app.accessibility

import android.content.Context
import android.util.Log
import com.androidagent.app.apps.AppCatalog
import com.androidagent.app.agent.ActionParser
import com.androidagent.app.agent.AgentAction
import com.androidagent.app.agent.AgentUiState
import com.androidagent.app.agent.SafetyGuard
import com.androidagent.app.data.SecureSettings
import com.androidagent.app.network.DeepSeekClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

object AgentController {
    private const val TAG = "AndroidAgent"
    private const val MAX_STEPS = 15
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutableState = MutableStateFlow(AgentUiState())
    val state: StateFlow<AgentUiState> = mutableState.asStateFlow()
    private var runJob: Job? = null

    fun setAccessibilityConnected(connected: Boolean) = update { copy(accessibilityConnected = connected) }
    fun setCurrentPackage(packageName: String) = update { copy(currentPackage = packageName) }

    fun start(context: Context, settings: SecureSettings) {
        if (runJob?.isActive == true) return
        val apiKey = settings.apiKey
        val configuredTarget = settings.targetPackage.ifBlank { null }
        val goal = settings.taskGoal
        if (apiKey.isBlank() || goal.isBlank()) {
            log("API key and task are required")
            return
        }
        val service = AgentAccessibilityService.current()
        if (service == null) {
            log("Accessibility service is not connected")
            return
        }
        runJob = scope.launch {
            update { copy(running = true, step = 0, status = "Preparing") }
            val history = mutableListOf<String>()
            val appCatalog = AppCatalog(context.applicationContext)
            val apps = appCatalog.list()
            val packages = apps.mapTo(mutableSetOf()) { it.packageName }
            var lockedPackage = configuredTarget?.let { target ->
                apps.firstOrNull { it.packageName.equals(target, true) }?.packageName
                    ?: apps.singleOrNull { it.label.equals(target, true) }?.packageName
                    ?: apps.singleOrNull { it.label.contains(target, true) || target.contains(it.label, true) }?.packageName
            }
            var lastActionSignature: String? = null
            try {
                if (configuredTarget != null && lockedPackage == null) {
                    log("Default app not found; falling back to automatic app selection")
                }
                for (step in 1..MAX_STEPS) {
                    update { copy(step = step, status = "Observing") }
                    val observation = service.observe()
                    setCurrentPackage(observation.packageName)
                    update { copy(status = "Planning") }
                    val raw = DeepSeekClient().plan(
                        apiKey = apiKey,
                        baseUrl = settings.modelBaseUrl,
                        model = settings.modelName,
                        goal = goal,
                        allowedPackage = lockedPackage,
                        appCatalog = apps.joinToString("\n") { "${it.label} | ${it.packageName}" }.take(16_000),
                        observation = observation,
                        history = history,
                    )
                    val action = ActionParser.parse(raw)
                    SafetyGuard.validate(action, observation, lockedPackage, packages).getOrThrow()
                    if (action is AgentAction.LaunchApp && lockedPackage == null) {
                        lockedPackage = action.packageName
                        log("Target locked: ${apps.firstOrNull { it.packageName == lockedPackage }?.label ?: lockedPackage}")
                    }
                    log("Step $step: ${action::class.simpleName}")
                    if (action is AgentAction.Finish) {
                        update { copy(status = "Succeeded: ${action.reason}") }
                        return@launch
                    }
                    if (action is AgentAction.Fail) error(action.reason)
                    val actionSignature = actionSignature(action)
                    if (actionSignature != null && actionSignature == lastActionSignature) {
                        log("Repeated action blocked after a successful execution")
                        update { copy(status = "Succeeded: action already completed") }
                        return@launch
                    }
                    update { copy(status = "Acting") }
                    require(service.execute(action, observation)) { "Action failed: $action" }
                    lastActionSignature = actionSignature
                    history += action.toString()
                    val completesTask = when (action) {
                        is AgentAction.ClickNode -> action.completeAfter
                        is AgentAction.ClickText -> action.completeAfter
                        else -> false
                    }
                    if (completesTask) {
                        log("Final action completed")
                        update { copy(status = "Succeeded: final action completed") }
                        return@launch
                    }
                    delay(if (action is AgentAction.Wait) action.milliseconds else 1200)
                }
                error("Maximum steps reached")
            } catch (_: CancellationException) {
                update { copy(status = "Cancelled") }
            } catch (error: Throwable) {
                Log.e(TAG, "Agent run failed", error)
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
        update { copy(logs = (listOf(message) + logs).take(40)) }
    }

    private fun actionSignature(action: AgentAction): String? = when (action) {
        is AgentAction.ClickNode -> "node:${action.nodeId}"
        is AgentAction.ClickText -> "text:${action.text.lowercase()}"
        else -> null
    }

    private inline fun update(block: AgentUiState.() -> AgentUiState) {
        mutableState.value = mutableState.value.block()
    }
}
