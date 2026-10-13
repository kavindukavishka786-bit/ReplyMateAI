package com.replymate.ai.logic

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OutgoingLoopPreventionTest {
    @Test fun onlyDistinctIdentifiedSendersCountAsIncoming() {
        assertTrue(IncomingReplySafety.isDefinitelyIncoming("Alex", "contact-7", "Me", "owner-1"))
        assertFalse(IncomingReplySafety.isDefinitelyIncoming(null, null, "Me", "owner-1"))
        assertFalse(IncomingReplySafety.isDefinitelyIncoming("Me", "owner-1", "Me", "owner-1"))
        assertFalse(IncomingReplySafety.isDefinitelyIncoming("Other display name", "owner-1", "Me", "owner-1"))
        assertFalse(IncomingReplySafety.isDefinitelyIncoming("Me", null, null, null))
        assertFalse(IncomingReplySafety.isDefinitelyIncoming("You", null, null, null))
        assertFalse(IncomingReplySafety.isDefinitelyIncoming("Owner", null, "owner", null))
    }

    @Test fun directReplyCannotTriggerImmediateSecondReply() {
        val t = 12345000L
        ReplyLoopGuard.markOutgoing("FeedbackTestFriend", "Hey! I'm an AI assistant. What's up?", t)
        assertTrue(ReplyLoopGuard.shouldIgnore("FeedbackTestFriend",
            "Hey! I'm an AI assistant. What's up?", t + 200L))
        assertTrue(ReplyLoopGuard.shouldIgnore("FeedbackTestFriend",
            "You: Hey! I'm an AI assistant. What's up?", t + 23000L))
        assertTrue(ReplyLoopGuard.shouldIgnore("FeedbackTestFriend",
            "Another notification update", t + 10000L))
        assertFalse(ReplyLoopGuard.shouldIgnore("FeedbackTestFriend",
            "New message after cooldown", t + 25000L))
        assertFalse(ReplyLoopGuard.shouldIgnore("FeedbackTestDifferentFriend",
            "New message", t + 1000L))
        assertFalse(ReplyLoopGuard.shouldIgnore("FeedbackTestFriend",
            "Hey! I'm an AI assistant. What's up?", t + 610000L))
    }

    @Test fun ownPrefixesAreRejected() {
        assertTrue(IncomingReplySafety.isOwnTextPrefix("You: How are you?"))
        assertTrue(IncomingReplySafety.isOwnTextPrefix(" Me: okay"))
        assertFalse(IncomingReplySafety.isOwnTextPrefix("Someone: how are you?"))
    }
}
