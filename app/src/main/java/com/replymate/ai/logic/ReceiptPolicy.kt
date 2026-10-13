package com.replymate.ai.logic

/**
 * Pure duplicate detection for WhatsApp notification refreshes.
 * Exact receipts stay suppressed for the persistence TTL; same content
 * with a refreshed platform timestamp is suppressed only briefly.
 */
object ReceiptPolicy {
    fun isDuplicate(
        receipts: List<Triple<String, String, Long>>,
        exact: String,
        loose: String,
        now: Long,
        refreshWindowMs: Long = 12_000L
    ): Boolean = receipts.any { (storedExact, storedLoose, receivedAt) ->
        storedExact == exact ||
            (storedLoose == loose && now - receivedAt in 0L..refreshWindowMs)
    }
}
