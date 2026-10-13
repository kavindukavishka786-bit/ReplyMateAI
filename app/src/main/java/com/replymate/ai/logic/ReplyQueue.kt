package com.replymate.ai.logic

import android.app.Notification
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import com.replymate.ai.data.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

data class PendingReply(
    val id: String,
    val sender: String,
    val incoming: String,
    val draft: String,
    val action: Notification.Action,
    val group: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
)

/** In-memory only; private message contents are never persisted. Review items disappear if
 * Android stops the process. This is intentional, and unavailable reply actions fail closed.
 */
object ReplyQueue {
    private val internal = MutableStateFlow<List<PendingReply>>(emptyList())
    val items = internal.asStateFlow()

    @Synchronized fun add(sender: String, incoming: String, draft: String, action: Notification.Action, group: Boolean = false): String {
        val id = UUID.randomUUID().toString()
        internal.value = (listOf(PendingReply(id, sender, incoming, draft, action, group)) + internal.value).take(10)
        return id
    }

    @Synchronized fun discard(id: String) {
        internal.value = internal.value.filterNot { it.id == id }
    }

    // Remove atomically before suspension: two taps cannot send the same draft.
    private val approvalMutex = ReplySendGate.mutex

    suspend fun approve(context: Context, id: String, reply: String): Boolean = approvalMutex.withLock {
        val draft = synchronized(this) {
            internal.value.find { it.id == id }?.also { discard(id) }
        } ?: return@withLock false
        val age = System.currentTimeMillis() - draft.timestamp
        val repo = SettingsRepository(context.applicationContext)
        val current = repo.settings.first()
        if (age !in 0L..5 * 60_000L || !ListenerRecovery.hasAccess(context) ||
            !SendSafety.permits(current, draft.sender, draft.group, draft.incoming, reply)) {
            return@withLock false
        }
        val sent = ReplyTransport.send(context, draft.action, reply, draft.sender)
        if (sent) {
            ConversationMemory.record(draft.sender, draft.incoming, reply)
            repo.countSent()
        }
        sent
    }

}

object ReplySendGate { val mutex = Mutex() }

object ReplyTransport {
    /** Reply through WhatsApp's supplied notification PendingIntent, not a private API. */
    fun send(context: Context, action: Notification.Action, reply: String, recipient: String): Boolean = runCatching {
        val inputs = action.remoteInputs?.filter { it.allowFreeFormInput } ?: emptyList()
        require(action.actionIntent?.creatorPackage == "com.whatsapp") { "Unexpected reply target" }
        require(reply.isNotBlank() && reply.length <= 700) { "Invalid reply" }
        require(ListenerRecovery.hasAccess(context)) { "Notification access revoked" }
        require(inputs.isNotEmpty()) { "No compatible reply action" }
        val intent = Intent().addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        val values = android.os.Bundle()
        inputs.forEach { values.putCharSequence(it.resultKey, reply) }
        RemoteInput.addResultsToIntent(inputs.toTypedArray(), intent, values)
        // Register before PendingIntent.send: it can synchronously re-post WhatsApp notifications.
        check(IncomingReceiptGuard.getInstance(context).markOutgoing(recipient, reply)) {
            "Cannot safely record outgoing reply"
        }
        ReplyLoopGuard.markOutgoing(recipient, reply)
        action.actionIntent.send(context, 0, intent)
    }.isSuccess
}
