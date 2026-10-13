package com.replymate.ai.logic

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Android NotificationListener callbacks start on main. CoroutineStart.UNDISPATCHED
 * executes immediately on that thread, despite the launch context saying IO.
 */
class ReplyDispatchTest {
    @Test fun undispatchedIoToIoDoesNotGuaranteeOffMainThread() = runBlocking {
        val caller = Thread.currentThread()
        val sameThread = async(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
            withContext(Dispatchers.IO) { Thread.currentThread() }
        }
        // This is precisely why the first v1.4.3 attempt was insufficient.
        assertSame(caller, sameThread.await())
    }

    @Test fun switchingToDefaultFirstForcesOffMainBeforeGemini() = runBlocking {
        val caller = Thread.currentThread()
        val worker = async(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
            withContext(Dispatchers.Default) {
                withContext(Dispatchers.IO) { Thread.currentThread() }
            }
        }
        assertNotSame(caller, worker.await())
    }
}
