package com.androidagent.app.automation

import android.content.Context
import android.os.PowerManager
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.androidagent.app.accessibility.AgentController
import com.androidagent.app.data.SecureSettings
import com.androidagent.app.agent.RuntimeOutcome
import com.androidagent.app.agent.RuntimeResult
import com.androidagent.app.agent.SensitiveOperationPolicy
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.security.MessageDigest
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

data class ScheduleRequest(
    val taskId: String,
    val goal: String,
    val triggerAtMillis: Long,
)

object ScheduleCommandParser {
    fun isCommand(input: String): Boolean = input.trim().startsWith("/schedule", ignoreCase = true)

    /** Explicit UI syntax: /schedule <triggerAtMillis>|<goal>. */
    fun parse(input: String): ScheduleRequest? {
        if (!isCommand(input)) return null
        val payload = input.trim().substringAfter(' ', "").trim()
        val parts = payload.split('|', limit = 2)
        if (parts.size != 2) return null
        val triggerAtMillis = parts[0].trim().toLongOrNull() ?: return null
        val goal = parts[1].trim()
        if (triggerAtMillis <= System.currentTimeMillis() || goal.isBlank()) return null
        val taskId = "manual-${stableId(triggerAtMillis.toString() + "|" + goal)}"
        return ScheduleRequest(taskId, goal, triggerAtMillis)
    }

    private fun stableId(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .take(8)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

object ScheduledTaskScheduler {
    fun schedule(
        context: Context,
        request: ScheduleRequest,
        policy: ExistingWorkPolicy = ExistingWorkPolicy.REPLACE,
    ) {
        require(request.taskId.isNotBlank()) { "taskId must not be blank" }
        require(request.goal.isNotBlank()) { "goal must not be blank" }
        SensitiveOperationPolicy.validateGoal(request.goal).getOrThrow()
        val delayMillis = (request.triggerAtMillis - System.currentTimeMillis()).coerceAtLeast(1_000L)
        SecureSettings(context).apply {
            scheduledTaskId = request.taskId
            scheduledTaskGoal = request.goal
            nextRunAt = request.triggerAtMillis
        }
        val work = OneTimeWorkRequestBuilder<ScheduledAgentWorker>()
            .setInputData(workDataOf("task_id" to request.taskId, "task_goal" to request.goal))
            .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(uniqueName(request.taskId), policy, work)
    }

    fun scheduleFromOcr(
        context: Context,
        taskId: String,
        goal: String,
        ocrText: String,
        now: LocalDateTime = LocalDateTime.now(),
    ): Long? {
        val next = NextRunTimeParser.parse(ocrText, now) ?: return null
        schedule(context, ScheduleRequest(taskId, goal, next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()))
        return next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }

    fun cancel(context: Context, taskId: String? = null) {
        val settings = SecureSettings(context)
        val id = taskId ?: settings.scheduledTaskId
        if (id.isNotBlank()) WorkManager.getInstance(context).cancelUniqueWork(uniqueName(id))
        settings.apply {
            scheduledTaskId = ""
            scheduledTaskGoal = ""
            nextRunAt = 0L
        }
    }

    fun uniqueName(taskId: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(taskId.toByteArray())
            .take(12)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return "scheduled_agent_task_$digest"
    }
}

enum class WorkerDecisionType { SUCCESS, RETRY, FAILURE }

data class ScheduledWorkerDecision(
    val type: WorkerDecisionType,
    val outcome: RuntimeOutcome,
    val reason: String,
)

object ScheduledWorkerResultMapper {
    fun map(
        runtimeResult: RuntimeResult?,
        accessibilityConnected: Boolean,
        agentBusy: Boolean,
        timedOut: Boolean,
        runAttemptCount: Int,
    ): ScheduledWorkerDecision {
        val result = runtimeResult ?: when {
            timedOut -> RuntimeResult.failure(RuntimeOutcome.TIMEOUT, "Scheduled run timed out")
            agentBusy -> RuntimeResult.failure(RuntimeOutcome.AGENT_BUSY, "Agent is already running")
            !accessibilityConnected -> RuntimeResult.failure(RuntimeOutcome.ACCESSIBILITY_DISCONNECTED, "Accessibility service is disconnected")
            else -> RuntimeResult.failure(RuntimeOutcome.INTERNAL_ERROR, "Scheduled run returned no result")
        }
        val retryable = result.outcome in setOf(
            RuntimeOutcome.TRANSIENT_NETWORK_ERROR,
            RuntimeOutcome.ACCESSIBILITY_DISCONNECTED,
            RuntimeOutcome.AGENT_BUSY,
            RuntimeOutcome.TIMEOUT,
        )
        val type = when {
            result.outcome == RuntimeOutcome.SUCCESS -> WorkerDecisionType.SUCCESS
            retryable && runAttemptCount < 3 -> WorkerDecisionType.RETRY
            else -> WorkerDecisionType.FAILURE
        }
        return ScheduledWorkerDecision(type, result.outcome, result.reason)
    }
}

class ScheduledAgentWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val settings = SecureSettings(applicationContext)
        val goal = inputData.getString("task_goal").orEmpty().ifBlank { settings.scheduledTaskGoal }
        if (goal.isBlank() || settings.apiKey.isBlank()) {
            val decision = ScheduledWorkerResultMapper.map(
                runtimeResult = RuntimeResult.failure(RuntimeOutcome.PERMANENT_PLAN_ERROR, "Scheduled task is missing a goal or API key"),
                accessibilityConnected = AgentController.state.value.accessibilityConnected,
                agentBusy = AgentController.state.value.running,
                timedOut = false,
                runAttemptCount = runAttemptCount,
            )
            return if (decision.type == WorkerDecisionType.RETRY) Result.retry() else Result.failure()
        }
        val goalSafetyFailure = SensitiveOperationPolicy.validateGoal(goal).exceptionOrNull()
        if (goalSafetyFailure != null) return Result.failure()
        if (!AgentController.state.value.accessibilityConnected || AgentController.state.value.running) {
            val decision = ScheduledWorkerResultMapper.map(
                runtimeResult = null,
                accessibilityConnected = AgentController.state.value.accessibilityConnected,
                agentBusy = AgentController.state.value.running,
                timedOut = false,
                runAttemptCount = runAttemptCount,
            )
            return if (decision.type == WorkerDecisionType.RETRY) Result.retry() else Result.failure()
        }
        val wakeLock = (applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "Muse:ScheduledAgent")
        wakeLock.acquire(TimeUnit.MINUTES.toMillis(9))
        return try {
            AgentController.start(applicationContext, settings, goalOverride = goal)
            delay(1_000)
            val finished = withTimeoutOrNull(TimeUnit.MINUTES.toMillis(8)) {
                while (AgentController.state.value.running) delay(500)
                true
            } ?: false
            if (!finished) AgentController.stopWithReason("Scheduled run exceeded its worker deadline")
            val decision = ScheduledWorkerResultMapper.map(
                runtimeResult = AgentController.lastResult,
                accessibilityConnected = AgentController.state.value.accessibilityConnected,
                agentBusy = AgentController.state.value.running,
                timedOut = !finished,
                runAttemptCount = runAttemptCount,
            )
            when (decision.type) {
                WorkerDecisionType.SUCCESS -> Result.success()
                WorkerDecisionType.RETRY -> Result.retry()
                WorkerDecisionType.FAILURE -> Result.failure()
            }
        } finally {
            if (AgentController.state.value.running && AgentController.state.value.goal == goal) {
                AgentController.stopWithReason("Scheduled worker finished before the agent stopped")
            }
            if (wakeLock.isHeld) wakeLock.release()
        }
    }
}

internal object NextRunTimeParser {
    private val fullDate = Regex("(20\\d{2})[-/.年](\\d{1,2})[-/.月](\\d{1,2})日?\\s*(\\d{1,2})[:：](\\d{2})")
    private val tomorrow = Regex("(?:明天|tomorrow|next day)[^0-9]{0,12}(\\d{1,2})[:：](\\d{2})", RegexOption.IGNORE_CASE)
    private val clock = Regex("(\\d{1,2})[:：](\\d{2})")

    fun parse(text: String, now: LocalDateTime): LocalDateTime? {
        fullDate.find(text)?.let { match ->
            val (year, month, day, hour, minute) = match.destructured
            return runCatching { LocalDateTime.of(year.toInt(), month.toInt(), day.toInt(), hour.toInt(), minute.toInt()) }
                .getOrNull()?.takeIf { it.isAfter(now) }
        }
        tomorrow.find(text)?.let { match ->
            return validTime(match.groupValues[1], match.groupValues[2])?.let { now.toLocalDate().plusDays(1).atTime(it) }
        }
        clock.findAll(text).lastOrNull()?.let { match ->
            val time = validTime(match.groupValues[1], match.groupValues[2]) ?: return null
            var candidate = now.toLocalDate().atTime(time)
            if (!candidate.isAfter(now.plusMinutes(1))) candidate = candidate.plusDays(1)
            return candidate
        }
        return null
    }

    private fun validTime(hour: String, minute: String): LocalTime? =
        runCatching { LocalTime.parse("${hour.padStart(2, '0')}:${minute.padStart(2, '0')}", DateTimeFormatter.ofPattern("HH:mm")) }.getOrNull()
}
