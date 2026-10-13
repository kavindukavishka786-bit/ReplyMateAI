package com.replymate.ai.logic

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Process
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.robolectric.Shadows.shadowOf
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 34])
class NotificationIntakeTest {
    @Test fun refreshFloodDoesNotCrowdNewMessageOutOfGenerationQueue() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("incoming_receipts", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val service = Robolectric.buildService(WhatsAppListener::class.java).create()
        val now = System.currentTimeMillis()
        val sender = Person.Builder().setName("Alice").setKey("alice").build()
        val owner = Person.Builder().setName("Owner").setKey("owner").build()
        fun notification(body: String): StatusBarNotification {
            val pending = PendingIntent.getBroadcast(context, 8,
                Intent("com.replymate.TEST").setPackage(context.packageName), PendingIntent.FLAG_MUTABLE)
            shadowOf(pending).setCreatorPackage("com.whatsapp")
            val reply = NotificationCompat.Action.Builder(0, "Reply", pending)
                .addRemoteInput(RemoteInput.Builder("reply").build()).build()
            val style = NotificationCompat.MessagingStyle(owner).setGroupConversation(false)
                .addMessage(body, now, sender)
            val notification = NotificationCompat.Builder(context, "whatsapp")
                .setSmallIcon(android.R.drawable.ic_dialog_email).setContentTitle("Alice")
                .setShortcutId("alice-chat").setStyle(style).addAction(reply).build()
            return StatusBarNotification("com.whatsapp", "com.whatsapp", 1, null, 0, 0, 0,
                notification, Process.myUserHandle(), now)
        }
        try {
            repeat(30) { service.get().onNotificationPosted(notification("Hello")) }
            service.get().onNotificationPosted(notification("What time is it?"))
            withTimeout(15_000) {
                while (JSONArray(prefs.getString("receipts", "[]")).length() < 2) delay(10)
            }
            assertEquals(2, JSONArray(prefs.getString("receipts", "[]")).length())
        } finally { service.destroy() }
    }
}
