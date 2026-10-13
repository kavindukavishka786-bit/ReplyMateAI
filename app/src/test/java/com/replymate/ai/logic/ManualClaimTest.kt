package com.replymate.ai.logic

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
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
class ManualClaimTest {
    @Before fun clear() { ReplyQueue.items.value.forEach { ReplyQueue.discard(it.id) } }
    @Test fun simultaneousApprovalClaimsOnlyOneDraft() {
        val context = RuntimeEnvironment.getApplication()
        val pending = PendingIntent.getBroadcast(context, 1, Intent("test").setPackage(context.packageName),
            PendingIntent.FLAG_IMMUTABLE)
        val action = Notification.Action.Builder(null, "Reply", pending).build()
        val id = ReplyQueue.add("Alex", "A question", "A reply", action)
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(4)
        try {
            val results = (1..8).map { pool.submit<PendingReply?> { start.await(); ReplyQueue.claim(id) } }
            start.countDown()
            assertEquals(1, results.count { it.get(10, TimeUnit.SECONDS) != null })
            assertNull(ReplyQueue.claim(id))
            assertTrue(ReplyQueue.items.value.isEmpty())
        } finally { pool.shutdownNow() }
    }
}
