package com.replymate.ai.logic

/** Do not dispatch queued work that has become too old or has an invalid clock. */
object ReplyFreshness {
    fun isFresh(timestamp: Long, now: Long = System.currentTimeMillis()): Boolean =
        timestamp > 0 && now - timestamp in -10_000L..180_000L
}
