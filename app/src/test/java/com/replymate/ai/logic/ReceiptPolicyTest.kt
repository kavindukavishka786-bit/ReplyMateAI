package com.replymate.ai.logic

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiptPolicyTest {
    @Test fun notificationRefreshWithNewTimestampCannotSendSecondReply() {
        val previous = listOf(Triple("unique-event-1", "same-content", 10_000L))
        assertTrue(ReceiptPolicy.isDuplicate(previous, "new-timestamp", "same-content", 12_000L))
        assertTrue(ReceiptPolicy.isDuplicate(previous, "new-timestamp", "same-content", 21_000L))
    }

    @Test fun originalReceiptStaysBlockedAfterRefreshWindow() {
        val previous = listOf(Triple("original", "body-hash", 1_000L))
        assertTrue(ReceiptPolicy.isDuplicate(previous, "original", "body-hash", 75_000L))
        assertFalse(ReceiptPolicy.isDuplicate(previous, "a-new-message-event", "body-hash", 75_000L))
    }

    @Test fun differentMessagesStillReplyImmediately() {
        val previous = listOf(Triple("event-a", "hello", 10_000L))
        assertFalse(ReceiptPolicy.isDuplicate(previous, "event-b", "what-time", 10_100L))
        assertFalse(ReceiptPolicy.isDuplicate(previous, "event-c", "hello-other-contact", 11_000L))
    }
}
