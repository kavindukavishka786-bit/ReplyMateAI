package com.replymate.ai.logic

import org.junit.Assert.*
import org.junit.Test

class ReplyFreshnessTest {
    @Test fun expiredQueuedRepliesAreBlocked() {
        assertTrue(ReplyFreshness.isFresh(1_000, 181_000))
        assertFalse(ReplyFreshness.isFresh(1_000, 181_001))
        assertFalse(ReplyFreshness.isFresh(0, 1_000))
        assertTrue(ReplyFreshness.isFresh(11_000, 1_000))
        assertFalse(ReplyFreshness.isFresh(11_001, 1_000))
    }
}
