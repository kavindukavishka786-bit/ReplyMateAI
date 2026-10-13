package com.replymate.ai.logic

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.replymate.ai.data.SecretStore
import com.replymate.ai.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

/** Resume only a previously requested session after explicit restart opt-in. */
class BackgroundRecoveryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (!BackgroundStatus.restartAfterBoot(context)) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                withTimeout(8_000L) {
                    val settings = SettingsRepository(context.applicationContext).settings.first()
                    if (BackgroundPolicy.mayRecover(
                            BackgroundStatus.requested(context), BackgroundStatus.stoppedByUser(context),
                            settings.enabled, ListenerRecovery.hasAccess(context),
                            BackgroundStatus.notificationsAllowed(context), SecretStore(context).hasKey())) {
                        BackgroundStatus.recover(context)
                    }
                }
            } catch (problem: Exception) {
                BackgroundStatus.reportError("Restart could not complete. Open ReplyMate to resume background mode.")
            } finally { pending.finish() }
        }
    }
}
