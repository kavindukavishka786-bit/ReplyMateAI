package com.replymate.ai.logic

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.replymate.ai.MainActivity
import com.replymate.ai.data.ReplyMode
import com.replymate.ai.data.SecretStore
import com.replymate.ai.data.SettingsRepository
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Incoming WhatsApp notifications are parsed immediately, even if the Activity is closed.
 * Each contact gets its own ordered, bounded queue; different contacts can be served
 * concurrently. Actual Gemini latency and Android/WhatsApp delivery still vary.
 */
class WhatsAppListener : NotificationListenerService() {
    companion object {
        private val _connected = MutableStateFlow(false)
        val connected = _connected.asStateFlow()
        val isConnected: Boolean get() = _connected.value
        private const val MAX_PENDING_PER_CONTACT = 12
        private const val FRESH_MESSAGE_WINDOW_MS = 180_000L
        private const val SEEN_TTL_MS = 10 * 60_000L
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
    private val senderLocks = ConcurrentHashMap<String, Mutex>()
    private val waitingCounts = ConcurrentHashMap<String, AtomicInteger>()
    private val lastActions = ConcurrentHashMap<String, Notification.Action>()
    private val seen = mutableMapOf<String, Long>()
    private val lastSent = ConcurrentHashMap<String, Long>()

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn?.packageName != "com.whatsapp") return
        val notification = sbn.notification ?: return
        if ((notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return
        val extras = notification.extras ?: return
        val chat = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        if (chat.isBlank()) return
        val style = runCatching {
            NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)
        }.getOrNull() ?: return

        val group = (Build.VERSION.SDK_INT >= 28 &&
            extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false)) ||
            !extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE).isNullOrBlank()
        val action = notification.actions?.firstOrNull { item ->
            item.remoteInputs?.any { it.allowFreeFormInput } == true
        } ?: return
        lastActions[chat] = action
        val now = System.currentTimeMillis()

        // One notification may contain SEVERAL newly received messages. Using just
        // messages.lastOrNull() used to silently drop intermediate messages.
        for (message in style.messages.takeLast(MAX_PENDING_PER_CONTACT)) {
            val time = message.timestamp
            if (time <= 0L || now - time !in -10_000L..FRESH_MESSAGE_WINDOW_MS) continue
            val person = message.person ?: continue
            if (!IncomingReplySafety.isDefinitelyIncoming(
                    person.name?.toString(), person.key,
                    style.user.name?.toString(), style.user.key)) continue

            val body = message.text?.toString()?.trim().orEmpty()
            if (body.isBlank() || body.length > 4000 ||
                IncomingReplySafety.isOwnTextPrefix(body) ||
                body.matches(Regex("(?i)^\\d+ (new )?messages?$")) ||
                ReplyLoopGuard.shouldIgnore(chat, body)) continue

            // Timestamp + content + contact distinguish repeated "Hi" messages while
            // filtering notification refreshes containing an already seen message.
            val fingerprint = "$chat\\u0000${person.name}\\u0000$time\\u0000$body"
            val unseen = synchronized(seen) {
                seen.entries.removeAll { now - it.value > SEEN_TTL_MS }
                if (seen.containsKey(fingerprint)) false
                else {
                    if (seen.size >= 600) seen.clear()
                    seen[fingerprint] = now
                    true
                }
            }
            if (!unseen) continue
            enqueue(chat, body, group, action)
        }
    }

    private fun enqueue(chat: String, body: String, group: Boolean, action: Notification.Action) {
        val count = waitingCounts.computeIfAbsent(chat) { AtomicInteger(0) }
        if (count.incrementAndGet() > MAX_PENDING_PER_CONTACT) {
            count.decrementAndGet()
            return
        }
        val lock = senderLocks.computeIfAbsent(chat) { Mutex() }
        // UNDISPATCHED (the next coroutine is already suspended at the lock) makes
        // messages enter that contact's queue in notification arrival order.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                lock.withLock { process(chat, body, group, action) }
            } finally {
                count.decrementAndGet()
            }
        }
    }

    private suspend fun process(chat: String, body: String, group: Boolean, action: Notification.Action) {
        val repo = SettingsRepository(applicationContext)
        val cfg = repo.settings.first()
        if (!cfg.enabled || (group && !cfg.includeGroups) ||
            !ContactRules.isPermitted(chat, cfg.allowed, cfg.blocked, cfg.allowAll) ||
            ReplyLoopGuard.shouldIgnore(chat, body)) return

        val apiKey = SecretStore(applicationContext).read() ?: return
        if (cfg.mode == ReplyMode.AUTOMATIC) {
            if (cfg.sent >= cfg.dailyLimit) return
            if (!cfg.instantReplies &&
                System.currentTimeMillis() - (lastSent[chat] ?: 0L) < cfg.cooldownMinutes * 60_000L) return
        }

        // Only hold a partial wake lock while a reply is actually generated/sent.
        val wakeLock = runCatching {
            (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ReplyMateAI:GenerateReply")
                .apply { setReferenceCounted(false); acquire(120_000L) }
        }.getOrNull()
        try {
            val draft = GeminiClient.draft(
                apiKey, chat, body, cfg.personality, cfg.ownerContext,
                ConversationMemory.recent(chat)
            )
            repo.countGenerated()
            val latest = repo.settings.first()
            if (!latest.enabled || (group && !latest.includeGroups) ||
                !ContactRules.isPermitted(chat, latest.allowed, latest.blocked, latest.allowAll) ||
                ReplyLoopGuard.shouldIgnore(chat, body)) return

            if (latest.mode == ReplyMode.AUTOMATIC) {
                sendMutex.withLock {
                    val current = repo.settings.first()
                    val delaySatisfied = current.instantReplies ||
                        System.currentTimeMillis() - (lastSent[chat] ?: 0L) >=
                        current.cooldownMinutes * 60_000L
                    if (current.enabled && current.mode == ReplyMode.AUTOMATIC &&
                        (!group || current.includeGroups) &&
                        current.sent < current.dailyLimit && delaySatisfied &&
                        ContactRules.isPermitted(chat, current.allowed, current.blocked, current.allowAll) &&
                        !ReplyLoopGuard.shouldIgnore(chat, body)) {
                        // Updated WhatsApp notifications can replace the direct-reply action;
                        // try the most recent action for this same conversation.
                        val currentAction = lastActions[chat] ?: action
                        if (ReplyTransport.send(applicationContext, currentAction, draft, chat)) {
                            ConversationMemory.record(chat, body, draft)
                            lastSent[chat] = System.currentTimeMillis()
                            repo.countSent()
                        }
                    }
                }
            } else if (latest.mode == ReplyMode.MANUAL) {
                val id = ReplyQueue.add(chat, body, draft, lastActions[chat] ?: action)
                showDraftNotification(chat, id)
            }
        } catch (_: Exception) {
            // Never auto-send a canned reply when the API or network is down.
            repo.countSkipped()
        } finally {
            runCatching { if (wakeLock?.isHeld == true) wakeLock.release() }
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
