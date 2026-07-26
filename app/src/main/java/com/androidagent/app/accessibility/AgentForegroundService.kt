package com.androidagent.app.accessibility

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import com.androidagent.app.MainActivity
import com.androidagent.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Keeps the process alive and the CPU awake for the length of a run, and gives the
 * user a visible, stoppable indication that the agent is driving the device.
 *
 * Without this the run lived only as long as the system felt like keeping an
 * accessibility-bound process around, which on OEM builds with aggressive task
 * killers is not long. The accessibility service itself cannot serve this purpose:
 * it is bound, not started, so it does not raise the process to foreground priority.
 */
class AgentForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        scope.launch {
            AgentController.state.collectLatest { state ->
                if (!state.running && AgentController.currentRunId() == null) {
                    delay(STOP_GRACE_MILLIS)
                    if (AgentController.currentRunId() == null) stopSelf()
                } else if (
                    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                ) {
                    runCatching {
                        notificationManager().notify(NOTIFICATION_ID, buildNotification(state.goal, state.step, state.status))
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_RUN) {
            AgentController.stop()
            stopSelf()
            return START_NOT_STICKY
        }
        val state = AgentController.state.value
        val notification = buildNotification(state.goal, state.step, state.status)
        val enteredForeground = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }.onFailure {
            // A blocked foreground start must never take the run down with it.
            Log.w(TAG, "Could not enter the foreground; the run continues unprotected", it)
        }.isSuccess
        if (!enteredForeground) {
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        acquireWakeLock()
        // The run state lives in AgentController, not in this service, so a restart
        // after process death would have nothing to resume.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        wakeLock?.let { if (it.isHeld) runCatching { it.release() } }
        wakeLock = null
        scope.cancel()
        super.onDestroy()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = runCatching {
            (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
                .apply { setReferenceCounted(false); acquire(MAX_RUN_MILLIS) }
        }.getOrNull()
    }

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, getString(R.string.agent_channel_name), NotificationManager.IMPORTANCE_LOW).apply {
            description = getString(R.string.agent_channel_description)
            setShowBadge(false)
        }
        notificationManager().createNotificationChannel(channel)
    }

    private fun notificationManager() = getSystemService(NotificationManager::class.java)

    private fun buildNotification(goal: String, step: Int, status: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, AgentForegroundService::class.java).setAction(ACTION_STOP_RUN),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.agent_notification_title, step))
            .setContentText(goal.take(80).ifBlank { status })
            .setContentIntent(open)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.agent_overlay_stop), stop).build())
            .build()
    }

    companion object {
        private const val TAG = "AndroidAgent"
        private const val CHANNEL_ID = "muse_agent_run"
        private const val NOTIFICATION_ID = 4211
        private const val WAKE_LOCK_TAG = "Muse:AgentRun"
        private const val ACTION_STOP_RUN = "com.androidagent.app.STOP_RUN"
        private const val MAX_RUN_MILLIS = 30 * 60 * 1_000L
        private const val STOP_GRACE_MILLIS = 250L

        fun start(context: Context) {
            val intent = Intent(context.applicationContext, AgentForegroundService::class.java)
            runCatching { context.applicationContext.startForegroundService(intent) }
                .onFailure { Log.w(TAG, "Foreground service start rejected", it) }
        }
    }
}
