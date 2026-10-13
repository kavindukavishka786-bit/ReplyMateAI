package com.replymate.ai.logic

import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import org.json.JSONArray

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 34])
class IncomingReceiptGuardTest {
    private val context get() = RuntimeEnvironment.getApplication()
    @Before fun clear() { context.getSharedPreferences("incoming_receipts", Context.MODE_PRIVATE).edit().clear().commit() }

    @Test fun delayedRefreshAndRestartNeverReadmitTheSameBody() {
        val guard = IncomingReceiptGuard(context)
        assertTrue(guard.accept("Alice", "alice", 1_000, "How are you?", 10_000))
        // Regression: 1.5.0 accepts this changed timestamp after 12 seconds.
        assertFalse(guard.accept("Alice", "alice", 2_000, "How are you?", 45_000))
        assertFalse(IncomingReceiptGuard(context).accept("Alice", "alice", 3_000, "How are you?", 120_000))
        // Alias remains blocked beyond the loose content window.
        assertFalse(IncomingReceiptGuard(context).accept("Alice", "alice", 2_000, "How are you?", 400_000))
    }

    @Test fun differentBodyAndDifferentConversationAreIndependent() {
        val guard = IncomingReceiptGuard(context)
        assertTrue(guard.accept("chat-1", "alice", 1_000, "Hi", 10_000))
        assertTrue(guard.accept("chat-1", "alice", 2_000, "What time?", 11_000))
        assertTrue(guard.accept("chat-2", "alice", 1_000, "Hi", 11_000))
        assertFalse(guard.accept("chat-1", "alice", 3_000, "Hi", 11_000))
        assertTrue(guard.accept("chat-1", "alice", 4_000, "Hi", 200_000))
    }

    @Test fun concurrentGuardInstancesAdmitOneReceiptOnly() {
        val pool = Executors.newFixedThreadPool(8)
        try {
            val calls = (1..16).map { Callable { IncomingReceiptGuard(context)
                .accept("chat", "author", 1_000, "Hello", 10_000) } }
            assertEquals(1, pool.invokeAll(calls).count { it.get() })
        } finally { pool.shutdownNow() }
    }

    @Test fun storedReceiptsContainNoMessageOrContactText() {
        assertTrue(IncomingReceiptGuard(context).accept("Private Contact", "Secret Author", 1_000, "Secret text", 10_000))
        val value = context.getSharedPreferences("incoming_receipts", Context.MODE_PRIVATE).getString("receipts", "")!!
        assertFalse(value.contains("Private Contact")); assertFalse(value.contains("Secret Author")); assertFalse(value.contains("Secret text"))
        assertEquals(1, JSONArray(value).length())
    }

    @Test fun corruptedStorageAndClockRollbackFailClosed() {
        val prefs = context.getSharedPreferences("incoming_receipts", Context.MODE_PRIVATE)
        prefs.edit().putString("receipts", "broken").commit()
        assertFalse(IncomingReceiptGuard(context).accept("chat", "author", 1_000, "Hello", 10_000))
        prefs.edit().clear().commit()
        val guard = IncomingReceiptGuard(context)
        assertTrue(guard.accept("chat", "author", 1_000, "Hello", 10_000))
        assertFalse(guard.accept("chat", "author", 2_000, "Hello", 5_000))
    }
}
