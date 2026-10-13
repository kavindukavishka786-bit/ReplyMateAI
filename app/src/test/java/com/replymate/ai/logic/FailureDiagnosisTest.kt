package com.replymate.ai.logic

import java.io.EOFException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FailureDiagnosisTest {
    @Test fun apiFailuresGetSpecificHintsWithoutRawPrivateText() {
        assertTrue(FailureDiagnosis.message(IllegalStateException("Gemini HTTP 429; secret chat")).contains("quota"))
        assertTrue(FailureDiagnosis.message(IllegalStateException("Gemini HTTP 403")).contains("API key"))
        assertTrue(FailureDiagnosis.message(IllegalStateException("Gemini HTTP 404")).contains("Model"))
        assertTrue(FailureDiagnosis.message(IllegalStateException("Gemini HTTP 400")).contains("Request"))
        val result = FailureDiagnosis.message(IllegalStateException("Gemini HTTP 503 apiKey=ABC"))
        assertFalse(result.contains("apiKey="))
        assertFalse(result.contains("ABC"))
    }

    @Test fun networkErrorsAreCategorized() {
        assertTrue(FailureDiagnosis.message(UnknownHostException("private data")).contains("network"))
        assertTrue(FailureDiagnosis.message(SocketTimeoutException("private data")).contains("timed out"))
        assertTrue(FailureDiagnosis.message(SocketException("Connection reset")).contains("interrupted"))
        assertTrue(FailureDiagnosis.message(EOFException("URL private-key")).contains("Connection closed"))
    }

    @Test fun unknownFailuresShowStageAndSafeExceptionClass() {
        val result = FailureDiagnosis.message(
            IllegalStateException("secret incoming message, private API key"),
            ReplyStage.RECORD
        )
        assertTrue(result.contains("Stage: Save counters"))
        assertTrue(result.contains("IllegalStateException"))
        assertFalse(result.contains("secret"))
        assertFalse(result.contains("private"))
    }

    @Test fun blockedEchoStillExplainsWhy() {
        assertTrue(
            FailureDiagnosis.message(IllegalStateException("Gemini repeated the incoming message"))
                .contains("blocked")
        )
    }
}
