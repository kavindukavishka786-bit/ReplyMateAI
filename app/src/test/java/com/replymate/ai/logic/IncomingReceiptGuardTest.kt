package com.replymate.ai.logic

import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 34])
class IncomingReceiptGuardTest {
    private lateinit var context: Context
    @Before fun clear() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("incoming_receipts", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun delayedChangedTimestampRefreshIsBlockedAfterReload() {
        val now = System.currentTimeMillis()
        val first = IncomingReceiptGuard(context)
        assertTrue(first.accept("Alex", "Alex", now, "Meet at 4?", now))
        val reloaded = IncomingReceiptGuard(context)
        assertFalse(reloaded.accept("Alex", "Alex", now + 90_000, "Meet at 4?", now + 90_000))
        assertTrue(reloaded.accept("Alex", "Alex", now + 90_001, "Actually 5?", now + 90_001))
    }

    @Test fun concurrentRefreshesReserveOnlyOnce() {
        val guard = IncomingReceiptGuard(context)
        val now = System.currentTimeMillis()
        val start = CountDownLatch(1)
        val workers = Executors.newFixedThreadPool(4)
        try {
            val results = (1..8).map { index -> workers.submit<Boolean> {
                start.await()
                guard.accept("Alex", "Alex", now + index, "Hello there", now + index)
            } }
            start.countDown()
            assertEquals(1, results.count { it.get(10, TimeUnit.SECONDS) })
        } finally { workers.shutdownNow() }
    }

    @Test fun outgoingEchoSurvivesRestartWithoutStoringPlaintext() {
        val now = System.currentTimeMillis()
        val guard = IncomingReceiptGuard(context)
        assertTrue(guard.markOutgoing("Private Contact", "My private response", now))
        assertTrue(guard.accept("Private Contact", "Private Contact", now, "Secret question", now))
        val reloaded = IncomingReceiptGuard(context)
        assertTrue(reloaded.isOutgoing("Private Contact", "You: My private response", now + 60_000))
        assertFalse(reloaded.isOutgoing("Another Contact", "My private response", now + 60_000))
        assertFalse(reloaded.isOutgoing("Private Contact", "A new question", now + 60_000))
        assertFalse(reloaded.isOutgoing("Private Contact", "My private response", now + 600_001))
        val stored = context.getSharedPreferences("incoming_receipts", Context.MODE_PRIVATE).all.toString()
        assertFalse(stored.contains("Private Contact"))
        assertFalse(stored.contains("My private response"))
        assertFalse(stored.contains("Secret question"))
    }

    @Test fun reservationsExpireAndOtherContactsAreIndependent() {
        val now = System.currentTimeMillis()
        val guard = IncomingReceiptGuard(context)
        assertTrue(guard.accept("Alex", "Alex", now, "hello", now))
        assertTrue(guard.accept("Sam", "Sam", now, "hello", now))
        assertFalse(guard.accept("Alex", "Alex", now + 10, " HELLO ", now + 800_000))
        assertTrue(guard.accept("Alex", "Alex", now + 900_001, "hello", now + 900_001))
    }
}
