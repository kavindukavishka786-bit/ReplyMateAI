package com.replymate.ai.logic

import android.Manifest
import android.content.pm.PackageManager
import com.replymate.ai.data.SettingsRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.replymate.ai.MainActivity
import com.replymate.ai.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Optional, explicitly user-started foreground service.
 *
 * WhatsApp message processing stays in the OS-bound WhatsAppListener; this service
 * only helps keep the application's process available while the UI is closed.
 * It does NOT poll messages, launch WhatsApp, or bypass Android battery restrictions.
 */
object BackgroundStatus {
    private val _running = MutableStateFlow(false)
    val running = _running.asStateFlow()
    internal fun setRunning(value: Boolean) { _running.value = value }

    private const val PREFS = "replymate_background_settings"
    private const val PREFERRED = "requested_by_user"
    private const val MANUALLY_STOPPED = "manually_stopped"
    private const val RESTART_BOOT = "restart_after_boot"
    private val _error = MutableStateFlow("")
    val error = _error.asStateFlow()
    internal fun reportError(value: String) { _error.value = value }

    fun notificationsAllowed(context: Context): Boolean =
        (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context,
            Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()

    fun restartAfterBoot(context: Context): Boolean = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(RESTART_BOOT, false)

    fun setRestartAfterBoot(context: Context, value: Boolean) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(RESTART_BOOT, value).apply()
    }

    fun requested(context: Context): Boolean = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(PREFERRED, false)

    fun stoppedByUser(context: Context): Boolean = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(MANUALLY_STOPPED, false)

    internal fun rememberRequested(context: Context, enabled: Boolean) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(PREFERRED, enabled).apply()
    }

    fun start(context: Context): Boolean {
        if (!notificationsAllowed(context) || !ListenerRecovery.hasAccess(context)) return false
        // Persist intent BEFORE Android may enter onStartCommand. Repeated starts are idempotent.
        val saved = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(PREFERRED, true).putBoolean(MANUALLY_STOPPED, false).commit()
        if (!saved) return false
        return recover(context)
    }

    internal fun recover(context: Context): Boolean {
        if (!requested(context) || stoppedByUser(context) || !notificationsAllowed(context) ||
            !ListenerRecovery.hasAccess(context)) return false
        return runCatching {
            ContextCompat.startForegroundService(context.applicationContext,
                Intent(context.applicationContext, BackgroundKeepAliveService::class.java))
            reportError("")
            true
        }.getOrElse {
            // Retain the user's choice for a later visible retry; never claim it is running.
            reportError("Android could not start background mode. Open ReplyMate and check app battery settings.")
            false
        }
    }

    fun stop(context: Context) {
        rememberRequested(context, false)
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(MANUALLY_STOPPED, true).apply()
        context.applicationContext.stopService(
            Intent(context.applicationContext, BackgroundKeepAliveService::class.java)
        )
        setRunning(false)
    }
}

class BackgroundKeepAliveService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var monitor: Job? = null
    companion object {
        private const val CHANNEL = "replymate_background"
        private const val NOTIFICATION_ID = 39011
        private const val ACTION_STOP = "com.replymate.ai.STOP_BACKGROUND"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            BackgroundStatus.rememberRequested(this, false)
            getSharedPreferences("replymate_background_settings", Context.MODE_PRIVATE).edit()
                .putBoolean("manually_stopped", true).apply()
            BackgroundStatus.setRunning(false)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        if (!BackgroundStatus.requested(this) || BackgroundStatus.stoppedByUser(this) ||
            !BackgroundStatus.notificationsAllowed(this) || !ListenerRecovery.hasAccess(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "ReplyMate background operation", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Visible while you choose to keep ReplyMate running" }
        )
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, BackgroundKeepAliveService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val status = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_replymate)
            .setContentTitle("ReplyMate AI • Background mode")
            .setContentText("Waiting for allowed WhatsApp notifications")
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .addAction(Notification.Action.Builder(null, "Stop background", stop).build())
            .build()

        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, status, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, status)
            }
        } catch (problem: RuntimeException) {
            BackgroundStatus.reportError("Android rejected background mode. Check permissions and reopen ReplyMate.")
            BackgroundStatus.setRunning(false)
            stopSelf()
            return START_NOT_STICKY
        }
        BackgroundStatus.setRunning(true)
        BackgroundStatus.reportError("")
        // No network/message polling and no permanent wake lock. Check only listener health.
        // One monitor per service, including repeated start commands and sticky restarts.
        if (monitor?.isActive != true) monitor = scope.launch {
            try {
                while (isActive) {
                    val cfg = SettingsRepository(applicationContext).settings.first()
                    if (!BackgroundPolicy.mayRecover(
                            BackgroundStatus.requested(this@BackgroundKeepAliveService),
                            BackgroundStatus.stoppedByUser(this@BackgroundKeepAliveService),
                            cfg.enabled, ListenerRecovery.hasAccess(this@BackgroundKeepAliveService),
                            BackgroundStatus.notificationsAllowed(this@BackgroundKeepAliveService),
                            // Key readability is checked by boot recovery and each reply.
                            // Binding health remains useful while the user replaces a key.
                            hasKey = true)) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                        break
                    }
                    ListenerRecovery.requestIfNeeded(this@BackgroundKeepAliveService)
                    delay(30_000L)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (problem: Exception) {
                BackgroundStatus.reportError("Background setup is unavailable. Open ReplyMate to check permissions.")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        BackgroundStatus.setRunning(false)
        super.onDestroy()
    }
}
