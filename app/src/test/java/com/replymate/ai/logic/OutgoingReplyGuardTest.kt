package com.replymate.ai.logic

import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 34])
class OutgoingReplyGuardTest {
    private val context get() = RuntimeEnvironment.getApplication()
    @Before fun clear() { context.getSharedPreferences("outgoing_replies", Context.MODE_PRIVATE).edit().clear().commit() }
    @Test fun echoesSurviveReloadWhileDifferentIncomingMessagesProceed() {
        val now = System.currentTimeMillis()
        assertTrue(OutgoingReplyGuard(context).markOutgoing("Private Contact", "Private reply", now))
        val reloaded = OutgoingReplyGuard(context)
        assertTrue(reloaded.isOutgoing("Private Contact", "You: Private reply", now + 90_000))
        assertFalse(reloaded.isOutgoing("Other Contact", "Private reply", now + 90_000))
        assertFalse(reloaded.isOutgoing("Private Contact", "A different question", now + 90_000))
        assertFalse(reloaded.isOutgoing("Private Contact", "Private reply", now + 600_001))
        val stored = context.getSharedPreferences("outgoing_replies", Context.MODE_PRIVATE).all.toString()
        assertFalse(stored.contains("Private Contact")); assertFalse(stored.contains("Private reply"))
    }
    @Test fun corruptStorageAndClockRollbackNeverAdmitAReply() {
        val prefs = context.getSharedPreferences("outgoing_replies", Context.MODE_PRIVATE)
        prefs.edit().putString("entries", "broken").commit()
        assertFalse(OutgoingReplyGuard(context).markOutgoing("Alex", "Reply"))
        assertTrue(OutgoingReplyGuard(context).isOutgoing("Alex", "Reply"))
        prefs.edit().clear().commit()
        val guard = OutgoingReplyGuard(context)
        assertTrue(guard.markOutgoing("Alex", "Reply", 10_000))
        assertTrue(guard.isOutgoing("Alex", "Reply", 5_000))
        assertFalse(guard.markOutgoing("Alex", "Another reply", 5_000))
    }
}
