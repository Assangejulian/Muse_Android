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
import com.androidagent.app.accessibility.AgentController
import com.androidagent.app.data.SecureSettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

object DailyTaskScheduler {
    private const val UNIQUE_WORK = "daily_coin_task"

    fun scheduleFromOcr(context: Context, goal: String, ocrText: String, now: LocalDateTime = LocalDateTime.now()): Long? {
        val next = NextRunTimeParser.parse(ocrText, now) ?: return null
        val triggerAt = next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val settings = SecureSettings(context)
        settings.scheduledGoal = goal
        settings.nextRunAt = triggerAt
        val delayMillis = Duration.between(now, next).toMillis().coerceAtLeast(1_000L)
        val request = OneTimeWorkRequestBuilder<DailyCoinWorker>()
            .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_WORK, ExistingWorkPolicy.REPLACE, request)
        return triggerAt
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK)
        SecureSettings(context).apply {
            scheduledGoal = ""
            nextRunAt = 0L
        }
    }
}

class DailyCoinWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val settings = SecureSettings(applicationContext)
        val goal = settings.scheduledGoal
        if (goal.isBlank() || settings.apiKey.isBlank()) return Result.failure()
        if (!AgentController.state.value.accessibilityConnected) return Result.retry()
        val wakeLock = (applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "Muse:DailyTask")
        wakeLock.acquire(TimeUnit.MINUTES.toMillis(9))
        return try {
            settings.taskGoal = goal
            AgentController.start(applicationContext, settings)
            delay(1_000)
            val finished = withTimeoutOrNull(TimeUnit.MINUTES.toMillis(8)) {
                while (AgentController.state.value.running) delay(500)
                true
            } ?: false
            if (finished && AgentController.state.value.status.startsWith("Succeeded")) Result.success() else Result.retry()
        } finally {
            if (wakeLock.isHeld) wakeLock.release()
        }
    }
}

internal object NextRunTimeParser {
    private val fullDate = Regex("(20\\d{2})[-/.年](\\d{1,2})[-/.月](\\d{1,2})日?\\s*(\\d{1,2})[:：](\\d{2})")
    private val tomorrow = Regex("(?:明天|次日|第二天)[^0-9]{0,12}(\\d{1,2})[:：](\\d{2})")
    private val clock = Regex("(\\d{1,2})[:：](\\d{2})")

    fun parse(text: String, now: LocalDateTime): LocalDateTime? {
        fullDate.find(text)?.let { match ->
            val (year, month, day, hour, minute) = match.destructured
            return runCatching { LocalDateTime.of(year.toInt(), month.toInt(), day.toInt(), hour.toInt(), minute.toInt()) }.getOrNull()
                ?.takeIf { it.isAfter(now) }
        }
        tomorrow.find(text)?.let { match ->
            return validTime(match.groupValues[1], match.groupValues[2])?.let { now.toLocalDate().plusDays(1).atTime(it) }
        }
        if (text.contains("下次") || text.contains("再来") || text.contains("可领取")) {
            clock.findAll(text).lastOrNull()?.let { match ->
                val time = validTime(match.groupValues[1], match.groupValues[2]) ?: return null
                var candidate = now.toLocalDate().atTime(time)
                if (!candidate.isAfter(now.plusMinutes(1))) candidate = candidate.plusDays(1)
                return candidate
            }
        }
        return null
    }

    private fun validTime(hour: String, minute: String): LocalTime? =
        runCatching { LocalTime.parse("${hour.padStart(2, '0')}:${minute.padStart(2, '0')}", DateTimeFormatter.ofPattern("HH:mm")) }.getOrNull()
}
