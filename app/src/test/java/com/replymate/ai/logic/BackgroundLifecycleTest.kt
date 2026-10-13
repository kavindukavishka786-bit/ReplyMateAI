package com.replymate.ai.logic

import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.app.Service
import android.provider.Settings
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 34])
class BackgroundLifecycleTest {
    @Test fun stickyRestartAfterExplicitStopDoesNotRestart() {
        val context = RuntimeEnvironment.getApplication()
        BackgroundStatus.stop(context)
        val controller = Robolectric.buildService(BackgroundKeepAliveService::class.java).create()
        try { assertEquals(Service.START_NOT_STICKY, controller.get().onStartCommand(null, 0, 1)) }
        finally { controller.destroy() }
        assertFalse(BackgroundStatus.running.value)
        assertFalse(BackgroundStatus.requested(context))
        assertTrue(BackgroundStatus.stoppedByUser(context))
    }
    @Test fun rebootRecoveryIsOffByDefaultAndStopRemainsRespected() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("replymate_background_settings", Context.MODE_PRIVATE).edit().clear().commit()
        assertFalse(BackgroundStatus.restartAfterBoot(context))
        BackgroundRecoveryReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertFalse(BackgroundStatus.requested(context))
        BackgroundStatus.setRestartAfterBoot(context, true)
        BackgroundStatus.stop(context)
        assertFalse(BackgroundStatus.recover(context))
    }
    @Test fun permissionMustBeForOurExactListenerComponent() {
        val context = RuntimeEnvironment.getApplication()
        Settings.Secure.putString(context.contentResolver, "enabled_notification_listeners",
            ComponentName(context.packageName, "OtherListener").flattenToString())
        assertFalse(ListenerRecovery.hasAccess(context))
        Settings.Secure.putString(context.contentResolver, "enabled_notification_listeners",
            ComponentName(context, WhatsAppListener::class.java).flattenToString())
        assertTrue(ListenerRecovery.hasAccess(context))
    }
}
