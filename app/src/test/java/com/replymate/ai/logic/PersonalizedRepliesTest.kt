package com.replymate.ai.logic

import org.junit.Assert.*
import org.junit.Test

class PersonalizedRepliesTest {
    @Test fun identicalInputIsBlocked() {
        assertTrue(ReplyQuality.isEcho("Hi", "Hi"))
        assertTrue(ReplyQuality.isEcho("Are you free tomorrow?", "Are you free tomorrow?"))
        assertTrue(ReplyQuality.isEcho("Please tell me the meeting time", "You said: Please tell me the meeting time"))
    }

    @Test fun actualRepliesAreAllowed() {
        assertFalse(ReplyQuality.isEcho("Hi", "Hey! I'm helping with messages. What's up?"))
        assertFalse(ReplyQuality.isEcho("Are you free tomorrow?", "I'll ask the owner to confirm a time."))
        assertFalse(ReplyQuality.isEcho("ඔයා කොහෙද?", "මම AI සහායකයෙක්. එයා ඉක්මනින් පිළිතුරු දෙයි."))
    }

    @Test fun conversationHistoryIsPerContactAndBounded() {
        val now = 123456789L
        ConversationMemory.record("TestFriendOne", "Hello", "Hey! How's it going?", now)
        assertEquals(1, ConversationMemory.recent("TestFriendOne", now).size)
        assertTrue(ConversationMemory.recent("TestFriendTwo", now).isEmpty())
        for (n in 1..7) {
            ConversationMemory.record("TestFriendOne", "Question $n", "Response $n", now + n)
        }
        assertEquals(4, ConversationMemory.recent("TestFriendOne", now + 8).size)
        assertEquals("Question 7", ConversationMemory.recent("TestFriendOne", now + 8).last().incoming)
        assertTrue(ConversationMemory.recent("TestFriendOne", now + (3 * 60 * 60 * 1000L)).isEmpty())
    }
}
