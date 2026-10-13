package com.replymate.ai.logic

import android.app.Service
import kotlinx.coroutines.runBlocking
import com.replymate.ai.data.SettingsRepository
import android.content.Context
import android.content.Intent
import android.content.ComponentName
import android.provider.Settings
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 34])
class BackgroundKeepAliveServiceTest {
    private val context get() = RuntimeEnvironment.getApplication()
    @Before fun clear() {
        context.getSharedPreferences("replymate_background_settings", Context.MODE_PRIVATE).edit().clear().commit()
        Settings.Secure.putString(context.contentResolver, "enabled_notification_listeners",
            ComponentName(context, WhatsAppListener::class.java).flattenToString())
        shadowOf(context).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        BackgroundStatus.setRunning(false)
    }

    @Test fun unrequestedStickyRestartStopsInsteadOfResurrecting() {
        val controller = Robolectric.buildService(BackgroundKeepAliveService::class.java).create()
        try {
            val service = controller.get()
            assertEquals(Service.START_NOT_STICKY, service.onStartCommand(null, 0, 1))
            assertTrue(shadowOf(service).isStoppedBySelf)
            assertFalse(BackgroundStatus.running.value)
        } finally { controller.destroy() }
    }

    @Test fun explicitStartStoresChoiceBeforeCommandAndStopPersists() {
        assertTrue(BackgroundStatus.start(context))
        assertTrue(BackgroundStatus.requested(context)); assertFalse(BackgroundStatus.stoppedByUser(context))
        BackgroundStatus.stop(context)
        assertFalse(BackgroundStatus.requested(context)); assertTrue(BackgroundStatus.stoppedByUser(context))
        assertFalse(BackgroundStatus.running.value)
    }

    @Test fun notificationStopSurvivesServiceRecreation() {
        BackgroundStatus.start(context)
        val controller = Robolectric.buildService(BackgroundKeepAliveService::class.java).create()
        try {
            assertEquals(Service.START_NOT_STICKY, controller.get().onStartCommand(
                Intent().setAction("com.replymate.ai.STOP_BACKGROUND"), 0, 1))
            assertTrue(BackgroundStatus.stoppedByUser(context))
            assertFalse(BackgroundStatus.requested(context))
            assertEquals(Service.START_NOT_STICKY, controller.get().onStartCommand(null, 0, 2))
        } finally { controller.destroy() }
    }

    @Test fun requestedStickyRestartPromotesVisibleForegroundService() {
        runBlocking { SettingsRepository(context).setEnabled(true) }
        Settings.Secure.putString(context.contentResolver, "enabled_notification_listeners",
            ComponentName(context, WhatsAppListener::class.java).flattenToString())
        BackgroundStatus.start(context)
        val controller = Robolectric.buildService(BackgroundKeepAliveService::class.java).create()
        try {
            val service = controller.get()
            assertEquals(Service.START_STICKY, service.onStartCommand(null, 0, 1))
            assertTrue(BackgroundStatus.running.value)
            assertNotNull(shadowOf(service).lastForegroundNotification)
        } finally { controller.destroy() }
        assertFalse(BackgroundStatus.running.value)
    }

    @Test fun anotherListenerInSamePackageDoesNotGrantReplyPermission() {
        Settings.Secure.putString(context.contentResolver, "enabled_notification_listeners",
            ComponentName(context.packageName, "OtherListener").flattenToString())
        assertFalse(ReplyPermissions.listenerAllowed(context))
        Settings.Secure.putString(context.contentResolver, "enabled_notification_listeners",
            ComponentName(context, WhatsAppListener::class.java).flattenToString())
        assertTrue(ReplyPermissions.listenerAllowed(context))
    }
}
