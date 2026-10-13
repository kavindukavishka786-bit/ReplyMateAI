package com.replymate.ai.logic

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactRulesTest {
    @Test fun safeDefaultIsNobodyAllowed() {
        assertFalse(ContactRules.isPermitted("Alex", "", "", false))
    }

    @Test fun exactCaseInsensitiveAllowListOnly() {
        assertTrue(ContactRules.isPermitted("aLeX", "Mom\nAlex", "", false))
        assertFalse(ContactRules.isPermitted("Alexander", "Alex", "", false))
    }

    @Test fun blockListAlwaysOverridesAllowAll() {
        assertFalse(ContactRules.isPermitted("Alex", "", "Alex", true))
        assertTrue(ContactRules.isPermitted("Sam", "", "Alex", true))
    }

    @Test fun emptySenderIsNeverPermitted() {
        assertFalse(ContactRules.isPermitted("  ", "", "", true))
    }
}
