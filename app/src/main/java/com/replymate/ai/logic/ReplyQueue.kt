package com.replymate.ai.logic

import android.app.Notification
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

data class PendingReply(
    val id: String,
    val sender: String,
    val incoming: String,
    val draft: String,
    val action: Notification.Action,
    val timestamp: Long = System.currentTimeMillis()
)

/** In-memory only; private message contents are never persisted. Review items disappear if
 * Android stops the process. This is intentional, and unavailable reply actions fail closed.
 */
object ReplyQueue {
    private val internal = MutableStateFlow<List<PendingReply>>(emptyList())
    val items = internal.asStateFlow()

    @Synchronized fun add(sender: String, incoming: String, draft: String, action: Notification.Action): String {
        val id = UUID.randomUUID().toString()
        internal.value = (listOf(PendingReply(id, sender, incoming, draft, action)) + internal.value).take(10)
        return id
    }

    @Synchronized fun discard(id: String) {
        internal.value = internal.value.filterNot { it.id == id }
    }

    @Synchronized fun approve(context: Context, id: String, reply: String): Boolean {
        val draft = internal.value.find { it.id == id } ?: return false
        if (reply.isBlank() || System.currentTimeMillis() - draft.timestamp > 5 * 60_000L) {
            discard(id)
            return false
        }
        val sent = ReplyTransport.send(context, draft.action, reply.take(700), draft.sender)
        if (sent) ConversationMemory.record(draft.sender, draft.incoming, reply)
        discard(id)
        return sent
    }
}

object ReplyTransport {
    /** Reply through WhatsApp's supplied notification PendingIntent, not a private API. */
    fun send(context: Context, action: Notification.Action, reply: String, recipient: String): Boolean = runCatching {
        val inputs = action.remoteInputs?.filter { it.allowFreeFormInput } ?: emptyList()
        require(inputs.isNotEmpty()) { "No compatible reply action" }
        val intent = Intent().addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        val values = android.os.Bundle()
        inputs.forEach { values.putCharSequence(it.resultKey, reply) }
        RemoteInput.addResultsToIntent(inputs.toTypedArray(), intent, values)
        // Register before PendingIntent.send: it can synchronously re-post WhatsApp notifications.
        ReplyLoopGuard.markOutgoing(recipient, reply)
        action.actionIntent.send(context, 0, intent)
    }.isSuccess
}
