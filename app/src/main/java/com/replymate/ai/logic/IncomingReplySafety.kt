package com.replymate.ai.logic

import java.security.MessageDigest
import java.util.Locale

/**
 * Strict direction check. A conversation notification isn't proof that its most recent
 * message came from the other person; a direct reply can update the same notification.
 * When the platform doesn't identify a distinct sender, DON'T automate it.
 */
object IncomingReplySafety {
    fun isDefinitelyIncoming(
        senderName: String?,
        senderKey: String?,
        ownerName: String?,
        ownerKey: String?
    ): Boolean {
        val name = senderName?.trim().orEmpty()
        if (name.isEmpty()) return false
        if (name.equals("you", true) || name.equals("me", true) ||
            name.equals("myself", true)) return false
        if (!ownerKey.isNullOrBlank() && !senderKey.isNullOrBlank() &&
            ownerKey == senderKey) return false
        if (!ownerName.isNullOrBlank() && name.equals(ownerName.trim(), true)) return false
        return true
    }

    fun normalizedBody(value: String): String = value.trim()
        .replace(Regex("^(you|me)\\s*:\\s*", RegexOption.IGNORE_CASE), "")
        .trim().lowercase(Locale.ROOT)
        .replace(Regex("\\s+"), " ")

    fun isOwnTextPrefix(value: String): Boolean =
        Regex("^\\s*(you|me)\\s*:", RegexOption.IGNORE_CASE).containsMatchIn(value)
}

/**
 * Stop outgoing messages from recursively triggering their own replies.
 * Only EXACT text matches are blocked; unlike earlier releases,
 * genuine new inbound messages are NOT delayed for an arbitrary 20 seconds.
 * Digests only; no outgoing text saved, and sender validation remains mandatory.
 */
object ReplyLoopGuard {
    private data class Outgoing(val messageDigest: String, val sentAt: Long)
    private val recent = mutableMapOf<String, MutableList<Outgoing>>()
    private const val RETAIN_MS = 10 * 60_000L

    private fun digest(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
    private fun who(value: String) = digest(value.trim().lowercase(Locale.ROOT))
    private fun what(value: String) = digest(IncomingReplySafety.normalizedBody(value))

    @Synchronized
    fun markOutgoing(recipient: String, body: String, now: Long = System.currentTimeMillis()) {
        prune(now)
        val entries = recent.getOrPut(who(recipient)) { mutableListOf() }
        entries.add(Outgoing(what(body), now))
        if (entries.size > 8) entries.removeAt(0)
    }

    @Synchronized
    fun shouldIgnore(recipient: String, body: String, now: Long = System.currentTimeMillis()): Boolean {
        prune(now)
        val entries = recent[who(recipient)] ?: return false
        return entries.any { it.messageDigest == what(body) }
    }

    private fun prune(now: Long) {
        recent.entries.removeAll { (_, entries) ->
            entries.removeAll { now - it.sentAt > RETAIN_MS || now < it.sentAt }
            entries.isEmpty()
        }
    }
}
