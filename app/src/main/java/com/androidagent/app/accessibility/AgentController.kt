package com.androidagent.app.accessibility

import android.content.Context
import android.util.Log
import com.androidagent.app.apps.AppCatalog
import com.androidagent.app.agent.ActionParser
import com.androidagent.app.agent.AgentAction
import com.androidagent.app.agent.AgentUiState
import com.androidagent.app.agent.SafetyGuard
import com.androidagent.app.agent.CompletionPolicy
import com.androidagent.app.data.SecureSettings
import com.androidagent.app.network.DeepSeekClient
import com.androidagent.app.automation.DailyTaskScheduler
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
    private const val MAX_STEPS = 24
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
            var repeatedActionCount = 0
            var failedActionCount = 0
            try {
                if (configuredTarget != null && lockedPackage == null) {
                    log("Default app not found; falling back to automatic app selection")
                }
                for (step in 1..MAX_STEPS) {
                    update { copy(step = step, status = "Observing") }
                    val observation = service.observe()
                    setCurrentPackage(observation.packageName)
                    update { copy(status = "Planning") }
                    val useVision = settings.visionEnabled && settings.visionApiKey.isNotBlank()
                    val screenshot = if (useVision) service.captureScreenDataUrl() else null
                    val planningApiKey = if (useVision) settings.visionApiKey else apiKey
                    val planningBaseUrl = if (useVision) settings.visionBaseUrl else settings.modelBaseUrl
                    val planningModel = if (useVision) settings.visionModelName else settings.modelName
                    val raw = DeepSeekClient().plan(
                        apiKey = planningApiKey,
                        baseUrl = planningBaseUrl,
                        model = planningModel,
                        goal = goal,
                        allowedPackage = lockedPackage,
                        appCatalog = apps.joinToString("\n") { "${it.label} | ${it.packageName}" }.take(16_000),
                        observation = observation,
                        history = history,
                        screenshotDataUrl = screenshot,
                    )
                    val action = ActionParser.parse(raw)
                    SafetyGuard.validate(action, observation, lockedPackage, packages).getOrThrow()
                    if (action is AgentAction.LaunchApp && lockedPackage == null) {
                        lockedPackage = action.packageName
                        log("Target locked: ${apps.firstOrNull { it.packageName == lockedPackage }?.label ?: lockedPackage}")
                    }
                    log("Step $step: ${action::class.simpleName}")
                    if (action is AgentAction.Finish) {
                        if (!CompletionPolicy.hasMinimumEvidence(goal, history)) {
                            history += "REJECTED_FINISH: local completion policy found too little successful progress"
                            log("Completion rejected locally; required steps are still missing")
                            continue
                        }
                        val verified = DeepSeekClient().verifyCompletion(
                            apiKey = planningApiKey,
                            baseUrl = planningBaseUrl,
                            model = planningModel,
                            goal = goal,
                            observation = observation,
                            history = history,
                            screenshotDataUrl = screenshot,
                        )
                        if (verified) {
                            completeTask(context, service, goal, action.reason)
                            return@launch
                        }
                        history += "REJECTED_FINISH: task evidence is incomplete"
                        log("Completion rejected; continuing the task")
                        continue
                    }
                    if (action is AgentAction.Fail) error(action.reason)
                    val actionSignature = actionSignature(action)
                    if (actionSignature != null && actionSignature == lastActionSignature) {
                        repeatedActionCount += 1
                        history += "BLOCKED_REPEAT: $actionSignature was already executed successfully; choose another action"
                        log("Repeated action blocked; asking for a different path")
                        if (repeatedActionCount >= 2) {
                            val recovery = if (action is AgentAction.ClickNode) AgentAction.Swipe("up") else AgentAction.Back
                            if (service.execute(recovery, observation)) {
                                history += "RECOVERY_ACTION: $recovery changed the screen after a repeated wrong action"
                                log("Applied a local recovery action")
                            }
                            lastActionSignature = null
                            repeatedActionCount = 0
                        }
                        delay(500)
                        continue
                    }
                    update { copy(status = "Acting") }
                    if (!service.execute(action, observation)) {
                        failedActionCount += 1
                        history += "FAILED_ACTION: $action could not be executed; choose a different target or focus the input first"
                        log("Action failed; replanning instead of ending the task")
                        lastActionSignature = null
                        if (failedActionCount >= 4) error("Four consecutive actions could not be executed")
                        delay(700)
                        continue
                    }
                    failedActionCount = 0
                    lastActionSignature = actionSignature
                    repeatedActionCount = 0
                    history += action.toString()
                    val completesTask = when (action) {
                        is AgentAction.ClickNode -> action.completeAfter
                        is AgentAction.ClickText -> action.completeAfter
                        else -> false
                    }
                    if (completesTask) {
                        log("Final action completed")
                        delay(1_500)
                        if (!CompletionPolicy.hasMinimumEvidence(goal, history)) {
                            history += "REJECTED_FINAL_CLICK: local completion policy found missing steps"
                            log("Final click rejected locally; continuing the task")
                            continue
                        }
                        val finalObservation = service.observe()
                        val finalScreenshot = if (useVision) service.captureScreenDataUrl() else null
                        val verified = DeepSeekClient().verifyCompletion(
                            planningApiKey,
                            planningBaseUrl,
                            planningModel,
                            goal,
                            finalObservation,
                            history,
                            finalScreenshot,
                        )
                        if (verified) {
                            completeTask(context, service, goal, "final action completed")
                            return@launch
                        }
                        history += "REJECTED_FINAL_CLICK: requested result is not yet visible"
                        log("Final click was not enough; continuing the task")
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

    private suspend fun completeTask(context: Context, service: AgentAccessibilityService, goal: String, reason: String) {
        if (goal.contains("金币") || goal.contains("签到") || goal.contains("领取")) {
            delay(1_000)
            val text = service.recognizeScreenText()
            val nextRun = DailyTaskScheduler.scheduleFromOcr(context.applicationContext, goal, text)
            if (nextRun != null) log("Next daily run scheduled from OCR: $nextRun")
            else log("Task completed, but no next-run time was found by OCR")
        }
        update { copy(status = "Succeeded: $reason") }
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
