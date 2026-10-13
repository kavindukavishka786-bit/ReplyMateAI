package com.replymate.ai.logic

import org.junit.Assert.*
import org.junit.Test

class BackgroundPolicyTest {
    @Test fun approvedSessionCanRecover() {
        assertTrue(BackgroundPolicy.mayRecover(true, false, true, true, true, true))
    }
    @Test fun stoppedDisabledOrIncompleteSetupNeverRecovers() {
        assertFalse(BackgroundPolicy.mayRecover(false, false, true, true, true, true))
        assertFalse(BackgroundPolicy.mayRecover(true, true, true, true, true, true))
        assertFalse(BackgroundPolicy.mayRecover(true, false, false, true, true, true))
        assertFalse(BackgroundPolicy.mayRecover(true, false, true, false, true, true))
        assertFalse(BackgroundPolicy.mayRecover(true, false, true, true, false, true))
        assertFalse(BackgroundPolicy.mayRecover(true, false, true, true, true, false))
    }
}
