package com.replymate.ai.logic

/** Checked at notification arrival, after queueing and immediately before a send. */
object ReplyFreshness {
    private const val WINDOW_MS = 180_000L
    fun isFresh(timestamp: Long, now: Long = System.currentTimeMillis()): Boolean =
        timestamp > 0L && now - timestamp in -10_000L..WINDOW_MS
}
