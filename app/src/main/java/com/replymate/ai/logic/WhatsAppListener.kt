package com.replymate.ai.logic

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationManagerCompat
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

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
        private const val MAX_INTAKE_PER_CONTACT = 128
        private const val FRESH_MESSAGE_WINDOW_MS = 180_000L
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        _connected.value = true
        // Recover only fresh OS notifications after a transient disconnect. Receipts
        // prevent replay of work already admitted before a process restart.
        runCatching { activeNotifications }.getOrNull()?.forEach { onNotificationPosted(it) }
    }

    override fun onListenerDisconnected() {
        _connected.value = false
        super.onListenerDisconnected()
        // When Android temporarily disconnects the listener, ask for a rebind
        // only if the user enabled background mode and permission still exists.
        // This is not a way around a user force-stop or revoked permission.
        if (BackgroundStatus.requested(applicationContext) &&
            ReplyPermissions.listenerAllowed(this)) {
            runCatching {
                NotificationListenerService.requestRebind(ComponentName(this, WhatsAppListener::class.java))
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // Shared with manual approval; accounting and dispatch never race.

    private val intakeCounts = ConcurrentHashMap<String, AtomicInteger>()
    private val intakeLocks = ConcurrentHashMap<String, Mutex>()
    private val senderLocks = ConcurrentHashMap<String, Mutex>()
    private val waitingCounts = ConcurrentHashMap<String, AtomicInteger>()
    private val lastActions = ConcurrentHashMap<String, Notification.Action>()
    private val receipts by lazy { IncomingReceiptGuard(applicationContext) }
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
        // A shortcut ID separates conversations with identical display names when
        // WhatsApp provides one. Never substitute a display name for a different action.
        val conversation = notification.shortcutId?.takeIf { it.isNotBlank() } ?: chat
        lastActions[conversation] = action
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

            // Android may re-post the SAME WhatsApp message with a different
            // MessagingStyle timestamp. Guard both exact receipts and very recent
            // same-body notification refreshes, persisting salted digests across restarts.
            offer(conversation, chat, person.key?.takeIf { it.isNotBlank() }
                ?: person.name?.toString().orEmpty(), person.name?.toString().orEmpty(), time, body, group, action)
        }
    }

    private fun offer(conversation: String, chat: String, author: String, authorName: String,
                      timestamp: Long, body: String, group: Boolean, action: Notification.Action) {
        val intakeCount = intakeCounts.computeIfAbsent(conversation) { AtomicInteger(0) }
        if (intakeCount.incrementAndGet() > MAX_INTAKE_PER_CONTACT) {
            intakeCount.decrementAndGet()
            return
        }
        val intake = intakeLocks.computeIfAbsent(conversation) { Mutex() }
        // Acquire in callback order, then move disk work off main. Duplicate refreshes
        // must not occupy the bounded generation queue and crowd out a NEW message.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                intake.withLock {
                    val admitted = withContext(Dispatchers.Default) {
                        withContext(Dispatchers.IO) {
                            ReplyFreshness.isFresh(timestamp) && receipts.accept(conversation, author,
                                timestamp, body, legacyChat = chat, legacyAuthor = authorName)
                        }
                    }
                    if (admitted) enqueue(conversation, chat, timestamp, body, group, action)
                }
            } finally { intakeCount.decrementAndGet() }
        }
    }

    private fun enqueue(conversation: String, chat: String, timestamp: Long, body: String,
                        group: Boolean, action: Notification.Action) {
        val count = waitingCounts.computeIfAbsent(conversation) { AtomicInteger(0) }
        if (count.incrementAndGet() > MAX_PENDING_PER_CONTACT) {
            count.decrementAndGet()
            return
        }
        val lock = senderLocks.computeIfAbsent(conversation) { Mutex() }
        // UNDISPATCHED acquires the per-contact mutex in notification arrival order.
        // CRITICAL: UNDISPATCHED starts executing on the caller's thread, which is
        // Android's main thread for NotificationListenerService callbacks. Switch
        // dispatchers BEFORE accessing the Gemini HTTP client. Otherwise a warm
        // DataStore read can make GeminiClient run on main and Android throws
        // NetworkOnMainThreadException even though scope's context contains IO.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                lock.withLock {
                    // The coroutine's context already says IO, but UNDISPATCHED
                    // starts on the callback thread. withContext(IO) alone can
                    // execute inline because its dispatcher equals this context.
                    // Force a *different* dispatcher first, then switch to IO.
                    withContext(Dispatchers.Default) {
                        withContext(Dispatchers.IO) {
                            process(conversation, chat, timestamp, body, group, action)
                        }
                    }
                }
            } finally {
                count.decrementAndGet()
            }
        }
    }

    private suspend fun process(conversation: String, chat: String, timestamp: Long, body: String, group: Boolean, action: Notification.Action) {
        val repo = SettingsRepository(applicationContext)
        var stage = ReplyStage.LOAD_SETTINGS
        var wakeLock: PowerManager.WakeLock? = null
        try {
            val cfg = repo.settings.first()
            stage = ReplyStage.CHECK_PERMISSIONS
            if (!ReplyFreshness.isFresh(timestamp) || !isConnected ||
                !ReplyPermissions.listenerAllowed(this) || !cfg.enabled || (group && !cfg.includeGroups) ||
                !ContactRules.isPermitted(chat, cfg.allowed, cfg.blocked, cfg.allowAll) ||
                ReplyLoopGuard.shouldIgnore(chat, body)) return

            val apiKey = SecretStore(applicationContext).read() ?: return
            if (cfg.mode == ReplyMode.AUTOMATIC) {
                if (cfg.sent >= cfg.dailyLimit) return
                if (!cfg.instantReplies &&
                    System.currentTimeMillis() - (lastSent[conversation] ?: 0L) < cfg.cooldownMinutes * 60_000L) return
            }

            // Hold CPU awake only while processing; the lock expires automatically.
            wakeLock = runCatching {
                (getSystemService(POWER_SERVICE) as PowerManager)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ReplyMateAI:GenerateReply")
                    .apply { setReferenceCounted(false); acquire(120_000L) }
            }.getOrNull()

            stage = ReplyStage.GENERATE
            val draft = GeminiClient.draft(
                apiKey, chat, body, cfg.personality, cfg.ownerContext,
                ConversationMemory.recent(conversation), cfg.modelId
            )
            stage = ReplyStage.SAVE_DRAFT
            repo.countGenerated()

            stage = ReplyStage.CONFIRM
            val latest = repo.settings.first()
            if (!ReplyFreshness.isFresh(timestamp) || !isConnected ||
                !ReplyPermissions.listenerAllowed(this) || !latest.enabled || (group && !latest.includeGroups) ||
                !ContactRules.isPermitted(chat, latest.allowed, latest.blocked, latest.allowAll) ||
                ReplyLoopGuard.shouldIgnore(chat, body)) return

            if (latest.mode == ReplyMode.AUTOMATIC) {
                ReplyDispatch.mutex.withLock {
                    stage = ReplyStage.CONFIRM
                    val current = repo.settings.first()
                    val delaySatisfied = current.instantReplies ||
                        System.currentTimeMillis() - (lastSent[conversation] ?: 0L) >=
                        current.cooldownMinutes * 60_000L
                    if (ReplyFreshness.isFresh(timestamp) && isConnected &&
                        ReplyPermissions.listenerAllowed(this) &&
                        current.enabled && current.mode == ReplyMode.AUTOMATIC &&
                        (!group || current.includeGroups) &&
                        current.sent < current.dailyLimit && delaySatisfied &&
                        ContactRules.isPermitted(chat, current.allowed, current.blocked, current.allowAll) &&
                        !ReplyLoopGuard.shouldIgnore(chat, body)) {
                        stage = ReplyStage.SEND
                        val currentAction = lastActions[conversation] ?: action
                        if (ReplyTransport.send(applicationContext, currentAction, draft, chat)) {
                            ConversationMemory.record(conversation, body, draft)
                            lastSent[conversation] = System.currentTimeMillis()
                            stage = ReplyStage.RECORD
                            repo.countSent()
                        } else {
                            repo.recordFailure(
                                "Stage: Send WhatsApp reply. Notification reply action failed or expired."
                            )
                        }
                    }
                }
            } else if (latest.mode == ReplyMode.MANUAL) {
                stage = ReplyStage.SAVE_DRAFT
                val id = ReplyQueue.add(chat, body, draft, lastActions[conversation] ?: action, group, conversation)
                showDraftNotification(chat, id)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (problem: Exception) {
            // Keep the error category and stage only, never the exception message.
            runCatching { repo.recordFailure(FailureDiagnosis.message(problem, stage)) }
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
