package com.replymate.ai.logic

import com.replymate.ai.data.AppSettings
import org.junit.Assert.*
import org.junit.Test

class SendSafetyTest {
    private val allowed = AppSettings(enabled = true, allowed = "Alex")
    private fun permits(settings: AppSettings) =
        SendSafety.permits(settings, "Alex", false, "When can we talk?", "The owner can confirm a time.")

    @Test fun approvedContactCanReceiveMeaningfulReply() { assertTrue(permits(allowed)) }
    @Test fun masterOffAndChangedContactRulesBlockOldDrafts() {
        assertFalse(permits(allowed.copy(enabled = false)))
        assertFalse(permits(allowed.copy(allowed = "Sam")))
        assertFalse(permits(allowed.copy(blocked = "Alex")))
    }
    @Test fun capsGroupsEchoAndEmptyOutputRemainBlocked() {
        assertFalse(permits(allowed.copy(sent = allowed.dailyLimit)))
        assertFalse(SendSafety.permits(allowed, "Alex", true, "Question?", "The owner will respond."))
        assertFalse(SendSafety.permits(allowed, "Alex", false, "Are you free tomorrow?", "Are you free tomorrow?"))
        assertFalse(SendSafety.permits(allowed, "Alex", false, "Question?", "  "))
        assertFalse(SendSafety.permits(allowed, "Alex", false, "Question?", "a".repeat(701)))
    }
}
