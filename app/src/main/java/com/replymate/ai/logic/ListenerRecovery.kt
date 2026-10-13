package com.replymate.ai.logic

import android.content.ComponentName
import android.content.Context
import android.os.SystemClock
import android.service.notification.NotificationListenerService

/** Rebind only the exact granted component, with a bounded retry rate. No message polling. */
object ListenerRecovery {
    private var lastAttempt = -30_000L

    fun hasAccess(context: Context): Boolean = android.provider.Settings.Secure.getString(
        context.contentResolver, "enabled_notification_listeners"
    ).orEmpty().split(':').any {
        ComponentName.unflattenFromString(it) == ComponentName(context, WhatsAppListener::class.java)
    }

    @Synchronized
    fun requestIfNeeded(context: Context): Boolean {
        if (WhatsAppListener.isConnected || !hasAccess(context)) return false
        val now = SystemClock.elapsedRealtime()
        if (now - lastAttempt < 30_000L) return false
        lastAttempt = now
        return runCatching {
            NotificationListenerService.requestRebind(ComponentName(context, WhatsAppListener::class.java))
            true
        }.getOrDefault(false)
    }
}
