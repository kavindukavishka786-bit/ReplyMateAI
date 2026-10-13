package com.replymate.ai.logic

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.os.PowerManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationCompat
import com.replymate.ai.MainActivity
import com.replymate.ai.data.ReplyMode
import com.replymate.ai.data.SecretStore
import com.replymate.ai.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Collections

class WhatsAppListener : NotificationListenerService() {
    companion object {
        private val _connected = MutableStateFlow(false)
        val connected = _connected.asStateFlow()
        val isConnected: Boolean get() = _connected.value
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        _connected.value = true
    }

    override fun onListenerDisconnected() {
        _connected.value = false
        super.onListenerDisconnected()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sendMutex = Mutex()
    private val processing = Collections.synchronizedSet(mutableSetOf<String>())
    private val seen = Collections.synchronizedMap(mutableMapOf<String, Long>())
    private val lastSent = Collections.synchronizedMap(mutableMapOf<String, Long>())

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null || sbn.packageName != "com.whatsapp") return
        val notification = sbn.notification ?: return
        if ((notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return
        val extras = notification.extras ?: return
        val sender = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        if (sender.isEmpty()) return

        // STRICT inbound-only parsing. EXTRA_TEXT alone cannot tell an incoming message
        // from our own direct reply; WhatsApp may repost the conversation notification.
        // Ignore ambiguous notification formats rather than risk an endless reply loop.
        val style = runCatching {
            NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)
        }.getOrNull() ?: return
        val latestMessage = style.messages.lastOrNull() ?: return
        val person = latestMessage.person ?: return
        if (!IncomingReplySafety.isDefinitelyIncoming(
                person.name?.toString(), person.key,
                style.user.name?.toString(), style.user.key)) return
        val incoming = latestMessage.text?.toString()?.trim().orEmpty()
        if (incoming.isBlank() || incoming.length > 4000 ||
            IncomingReplySafety.isOwnTextPrefix(incoming) ||
            incoming.matches(Regex("(?i)^\\d+ (new )?messages?$"))) return

        // If an outgoing reply triggers another notification, suppress it even when
        // WhatsApp's notification metadata isn't completely reliable.
        if (ReplyLoopGuard.shouldIgnore(sender, incoming)) return
        val group = (Build.VERSION.SDK_INT >= 28 &&
            extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false)) ||
            !extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE).isNullOrBlank()
        // Only works for notifications exposing the standard Android direct-reply action.
        val action = notification.actions?.firstOrNull { item ->
            item.remoteInputs?.any { it.allowFreeFormInput } == true
        } ?: return

        // Drop repeated notification updates and overlapping generation for one sender.
        val fingerprint = "$sender\u0000${latestMessage.timestamp}\u0000$incoming"
        val now = System.currentTimeMillis()
        synchronized(seen) {
            if (now - (seen[fingerprint] ?: 0L) < 90_000L) return
            seen[fingerprint] = now
            if (seen.size > 200) seen.entries.removeAll { now - it.value > 90_000L }
        }
        if (!processing.add(sender)) return
        // Keep CPU awake only while actively producing a reply, not all day.
        // Gemini can be slow when the display is off. The timed lock is released
        // in finally, and also expires automatically if Android kills our process.
        val wakeLock = runCatching {
            (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ReplyMateAI:GenerateReply")
                .apply { setReferenceCounted(false); acquire(120_000L) }
        }.getOrNull()
        scope.launch {
            val repository = SettingsRepository(applicationContext)
            try {
                val cfg = repository.settings.first()
                if (!cfg.enabled) return@launch
                if (group && !cfg.includeGroups) return@launch
                if (!ContactRules.isPermitted(sender, cfg.allowed, cfg.blocked, cfg.allowAll)) return@launch
                val apiKey = SecretStore(applicationContext).read() ?: return@launch
                val age = System.currentTimeMillis() - (lastSent[sender] ?: 0L)
                if (age < cfg.cooldownMinutes * 60_000L && cfg.mode == ReplyMode.AUTOMATIC) return@launch
                if (cfg.mode == ReplyMode.AUTOMATIC && cfg.sent >= cfg.dailyLimit) return@launch

                val draft = GeminiClient.draft(
                    apiKey, sender, incoming, cfg.personality, cfg.ownerContext,
                    ConversationMemory.recent(sender)
                )
                repository.countGenerated()
                // Recheck master toggle/mode after network call to prevent sends during shutdown.
                val latest = repository.settings.first()
                if (!latest.enabled || !ContactRules.isPermitted(sender, latest.allowed, latest.blocked, latest.allowAll)) return@launch
                // Mode/permissions may change while Gemini is generating the reply.
                // Never send a draft if we have recently sent to this conversation.
                if (ReplyLoopGuard.shouldIgnore(sender, incoming)) return@launch
                if (latest.mode == ReplyMode.AUTOMATIC) {
                    sendMutex.withLock {
                        val current = repository.settings.first()
                        if (current.enabled && current.mode == ReplyMode.AUTOMATIC &&
                            current.sent < current.dailyLimit &&
                            ContactRules.isPermitted(sender, current.allowed, current.blocked, current.allowAll) &&
                            (System.currentTimeMillis() - (lastSent[sender] ?: 0L)) >= current.cooldownMinutes * 60_000L) {
                            if (ReplyTransport.send(applicationContext, action, draft, sender)) {
                                ConversationMemory.record(sender, incoming, draft)
                                lastSent[sender] = System.currentTimeMillis()
                                repository.countSent()
                            }
                        }
                    }
                } else if (latest.mode == ReplyMode.MANUAL) {
                    val id = ReplyQueue.add(sender, incoming, draft, action)
                    showDraftNotification(sender, id)
                }
            } catch (_: Exception) {
                // Fail closed: API and network errors never trigger automatic fallback messages.
                repository.countSkipped()
            } finally {
                processing.remove(sender)
                runCatching { if (wakeLock?.isHeld == true) wakeLock.release() }
            }
        }
    }

    private fun showDraftNotification(sender: String, id: String) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel(
            "drafts", "ReplyMate review drafts", NotificationManager.IMPORTANCE_DEFAULT))
        val intent = Intent(this, MainActivity::class.java)
            .putExtra("open_reviews", true)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = PendingIntent.getActivity(this, id.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, "drafts")
            .setSmallIcon(com.replymate.ai.R.drawable.ic_replymate)
            .setContentTitle("ReplyMate • Review a draft")
            .setContentText("$sender • Tap to approve or discard")
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .build()
        manager.notify(id.hashCode(), notification)
    }

    override fun onDestroy() {
        _connected.value = false
        scope.cancel()
        super.onDestroy()
    }
}
