package com.replymate.ai.logic

import java.io.EOFException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import org.json.JSONException

/** Safe diagnostic stages, never populated from sender data. */
enum class ReplyStage(val display: String) {
    LOAD_SETTINGS("Load settings"),
    CHECK_PERMISSIONS("Check permissions and key"),
    GENERATE("Generate AI response"),
    SAVE_DRAFT("Record generated draft"),
    CONFIRM("Verify reply settings"),
    SEND("Send WhatsApp reply"),
    RECORD("Save counters"),
    TEST("Test Gemini")
}

/** No contacts, message text, raw HTTP response, stack traces or API keys are stored. */
object FailureDiagnosis {
    fun message(error: Exception, stage: ReplyStage = ReplyStage.GENERATE): String {
        val prefix = "Stage: ${stage.display}. "
        val detail = when (error) {
            is UnknownHostException, is ConnectException ->
                "No network connection to Gemini. Check mobile data and Wi-Fi."
            is SocketTimeoutException ->
                "Gemini timed out. Check internet stability and retry."
            is SSLException ->
                "HTTPS security failed. Check the device date, time and network."
            is EOFException ->
                "Connection closed while receiving Google's response. Retry on a stable network."
            is SocketException ->
                "Network connection was interrupted or reset. Check data signal and try again."
            is InterruptedIOException ->
                "Network operation was interrupted. Check the connection and retry."
            is IOException ->
                "Network read/write failed. Test using Wi-Fi or mobile data."
            is JSONException ->
                "The Gemini response could not be parsed (JSON). Test API access and retry."
            is SecurityException ->
                "Android denied the request. Review ReplyMate notification access and permissions."
            else -> classify(error)
        }
        return (prefix + detail).take(240)
    }

    private fun classify(error: Exception): String {
        val raw = error.message.orEmpty()
        val http = Regex("\\bHTTP\\s+(\\d{3})\\b").find(raw)
            ?.groupValues?.getOrNull(1)?.toIntOrNull()
        if (http != null) return when (http) {
            400 -> "Gemini HTTP 400: Request rejected. Check model/API configuration."
            401, 403 -> "Gemini HTTP $http: API key is invalid or lacks access."
            402 -> "Gemini HTTP 402: Project billing or credits are required."
            404 -> "Gemini HTTP 404: Model is unavailable to this API key."
            408, 500, 502, 503, 504 -> "Gemini HTTP $http: Temporary API failure. Try again."
            429 -> "Gemini HTTP 429: Project quota or rate limit reached."
            else -> "Gemini HTTP $http: Request rejected by Google."
        }
        if (raw.contains("produced no reply", true) || raw.contains("produced no text", true)) {
            return "Gemini produced no usable answer; nothing was sent."
        }
        if (raw.contains("repeated the incoming", true)) {
            return "Gemini repeated the incoming text; reply blocked."
        }
        if (raw.contains("notification reply action", true) ||
            raw.contains("no compatible reply action", true)) {
            return "WhatsApp notification has no usable reply action."
        }

        // Exception class is known from compiled code; never show exception.message,
        // which can contain private message text, endpoint credentials or file paths.
        val kind = when (error) {
            is NullPointerException -> "NullPointerException"
            is IllegalStateException -> "IllegalStateException"
            is IllegalArgumentException -> "IllegalArgumentException"
            is ClassCastException -> "ClassCastException"
            is UnsupportedOperationException -> "UnsupportedOperationException"
            else -> error.javaClass.simpleName.replace(Regex("[^A-Za-z0-9]"), "").take(32)
                .ifEmpty { "UnknownException" }
        }
        return "Unexpected $kind. Open Settings and run Test Gemini."
    }
}
