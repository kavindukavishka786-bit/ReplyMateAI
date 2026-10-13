package com.replymate.ai.logic

import java.net.SocketTimeoutException
import java.net.UnknownHostException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FailureDiagnosisTest {
    @Test fun apiFailureCodesGetClearHintsWithoutRawMessageText() {
        assertTrue(FailureDiagnosis.message(IllegalStateException("Gemini HTTP 429; secret test chat")).contains("quota"))
        assertTrue(FailureDiagnosis.message(IllegalStateException("Gemini HTTP 403")).contains("API key"))
        assertTrue(FailureDiagnosis.message(IllegalStateException("Gemini HTTP 404")).contains("model"))
        assertTrue(FailureDiagnosis.message(IllegalStateException("Gemini HTTP 400")).contains("request"))
    }

    @Test fun failureCategoriesAreHelpfulAndPrivate() {
        val diagnostic = FailureDiagnosis.message(IllegalStateException("Gemini HTTP 503 secret apiKey=XYZ"))
        assertFalse(diagnostic.contains("apiKey="))
        assertFalse(diagnostic.contains("XYZ"))
        assertTrue(FailureDiagnosis.message(UnknownHostException("private data")).contains("Network"))
        assertTrue(FailureDiagnosis.message(SocketTimeoutException("private data")).contains("timed out"))
        assertTrue(FailureDiagnosis.message(IllegalStateException("Gemini repeated the incoming message")).contains("blocked"))
    }
}
