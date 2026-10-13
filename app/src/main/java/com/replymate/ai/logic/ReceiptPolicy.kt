package com.replymate.ai.logic

/** WhatsApp has no stable message ID in notifications. Prefer suppressing an
 * ambiguous repeated body to sending twice. Distinct bodies remain eligible. */
object ReceiptPolicy {
    const val RETAIN_MS = 15 * 60_000L

    fun isDuplicate(
        receipts: List<Triple<String, String, Long>>,
        exact: String,
        loose: String,
        now: Long,
        refreshWindowMs: Long = RETAIN_MS
    ): Boolean = receipts.any { (storedExact, storedLoose, receivedAt) ->
        now - receivedAt in 0L..RETAIN_MS &&
            (storedExact == exact ||
                (storedLoose == loose && now - receivedAt <= refreshWindowMs))
    }
}
