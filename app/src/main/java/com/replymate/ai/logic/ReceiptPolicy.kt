package com.replymate.ai.logic

/**
 * Pure duplicate detection for WhatsApp notification refreshes.
 * Exact receipts stay suppressed for the persistence TTL; same content
 * with a refreshed platform timestamp is suppressed for the incoming freshness window.
 * Notification metadata cannot distinguish a refresh from a genuinely repeated identical
 * message, so the latter is deliberately suppressed too.
 */
object ReceiptPolicy {
    fun isDuplicate(
        receipts: List<Triple<String, String, Long>>,
        exact: String,
        loose: String,
        now: Long,
        refreshWindowMs: Long = 180_000L
    ): Boolean = receipts.any { (storedExact, storedLoose, receivedAt) ->
        storedExact == exact ||
            (storedLoose == loose && (now < receivedAt || now - receivedAt <= refreshWindowMs))
    }
}
