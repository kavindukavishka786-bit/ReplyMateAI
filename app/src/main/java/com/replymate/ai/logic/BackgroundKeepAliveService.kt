package com.replymate.ai.logic

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.service.notification.NotificationListenerService
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

    fun requested(context: Context): Boolean = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(PREFERRED, false)

    fun stoppedByUser(context: Context): Boolean = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(MANUALLY_STOPPED, false)

    internal fun rememberRequested(context: Context, enabled: Boolean) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(PREFERRED, enabled).apply()
    }

    fun start(context: Context): Boolean = runCatching {
        ContextCompat.startForegroundService(
            context.applicationContext,
            Intent(context.applicationContext, BackgroundKeepAliveService::class.java)
        )
        // Start only from a visible screen / explicit user action. Save the user's
        // choice for service recovery on returning to the Activity, not at boot.
        rememberRequested(context, true)
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(MANUALLY_STOPPED, false).apply()
        true
    }.getOrElse {
        rememberRequested(context, false)
        false
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

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, status, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, status)
        }
        BackgroundStatus.setRunning(true)

        // Foreground service continues independently of MainActivity. Swiping
        // the Activity away doesn't explicitly stop it (stopWithTask=false).
        // An Android force-stop or aggressive OEM task killer can still stop it.
        // The notification-listener permission is managed by Android.
        // The system binds notification listeners independently of this foreground service.
        // Request recovery only when it isn't already connected.
        if (!WhatsAppListener.isConnected &&
            NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)) {
            runCatching {
                NotificationListenerService.requestRebind(
                    ComponentName(this, WhatsAppListener::class.java)
                )
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        BackgroundStatus.setRunning(false)
        super.onDestroy()
    }
}
