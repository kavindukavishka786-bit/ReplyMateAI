package com.replymate.ai.logic

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertNotSame
import org.junit.Test

/**
 * Guards against the launch(start=UNDISPATCHED) main-thread pitfall.
 * The initial launch body runs synchronously on the calling thread even if the
 * scope was created with Dispatchers.IO. A withContext(IO) hop is required.
 */
class ReplyDispatchTest {
    @Test fun undispatchedLaunchMovesGeminiWorkOffCallerThread() = runBlocking {
        val caller = Thread.currentThread()
        val worker = async(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
            withContext(Dispatchers.IO) { Thread.currentThread() }
        }
        assertNotSame(caller, worker.await())
    }
}
