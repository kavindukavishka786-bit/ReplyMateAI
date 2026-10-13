package com.replymate.ai.logic

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.content.ComponentName
import android.provider.Settings
import com.replymate.ai.data.SettingsRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 34])
class ManualReplySafetyTest {
    private val context get() = RuntimeEnvironment.getApplication()
    @Before fun clear() {
        ReplyQueue.items.value.forEach { ReplyQueue.discard(it.id) }
        Settings.Secure.putString(context.contentResolver, "enabled_notification_listeners",
            ComponentName(context, WhatsAppListener::class.java).flattenToString())
        runBlocking {
            SettingsRepository(context).apply {
                setEnabled(true); setAllowAll(false); setAllowed("Alice"); setBlocked(""); setGroups(false)
            }
        }
    }
    private fun action(): Notification.Action {
        val intent = PendingIntent.getBroadcast(context, 125, Intent("com.replymate.TEST").setPackage(context.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
        return Notification.Action.Builder(null, "Reply", intent)
            .addRemoteInput(RemoteInput.Builder("text").setAllowFreeFormInput(true).build()).build()
    }

    @Test fun doubleApprovalCanDispatchOnlyOnce() = runBlocking {
        val listener = Robolectric.buildService(WhatsAppListener::class.java).create()
        try {
            listener.get().onListenerConnected()
            val id = ReplyQueue.add("Alice", "Hi", "Hey, how can I help?", action())
            val outcomes = listOf(async { ReplyQueue.approve(context, id, "Hey, how can I help?") },
                async { ReplyQueue.approve(context, id, "Hey, how can I help?") }).awaitAll()
            assertEquals(1, outcomes.count { it })
            assertFalse(ReplyQueue.items.value.any { it.id == id })
        } finally { listener.destroy() }
    }

    @Test fun newlyBlockedContactAndPausedAssistantCannotSend() = runBlocking {
        val listener = Robolectric.buildService(WhatsAppListener::class.java).create()
        try {
            listener.get().onListenerConnected()
            val id = ReplyQueue.add("Alice", "Hi", "Hey!", action())
            SettingsRepository(context).setBlocked("Alice")
            assertFalse(ReplyQueue.approve(context, id, "Hey!"))
            SettingsRepository(context).setBlocked("")
            val next = ReplyQueue.add("Alice", "Hi again", "Hey again!", action())
            SettingsRepository(context).setEnabled(false)
            assertFalse(ReplyQueue.approve(context, next, "Hey again!"))
        } finally { listener.destroy() }
    }

    @Test fun revokedPermissionAndGroupOptOutPreventManualSend() = runBlocking {
        val listener = Robolectric.buildService(WhatsAppListener::class.java).create()
        try {
            listener.get().onListenerConnected()
            assertTrue(WhatsAppListener.isConnected)
            val id = ReplyQueue.add("Alice", "Hi", "Hey!", action(), group = true)
            assertFalse(ReplyQueue.approve(context, id, "Hey!"))
            val next = ReplyQueue.add("Alice", "Hi", "Hey!", action())
            Settings.Secure.putString(context.contentResolver, "enabled_notification_listeners", "")
            assertTrue(WhatsAppListener.isConnected)
            assertFalse(ReplyQueue.approve(context, next, "Hey!"))
        } finally { listener.destroy() }
    }
}
