package com.replymate.ai.logic

import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * User-facing failure diagnostics: NO incoming message text, API key, contact name,
 * request body, stack trace, or raw server response is ever stored.
 */
object FailureDiagnosis {
    fun message(error: Exception): String {
        if (error is UnknownHostException || error is ConnectException) {
            return "Network unavailable. Connect to Wi-Fi or mobile data and try the AI test."
        }
        if (error is SocketTimeoutException) {
            return "Gemini request timed out. Check your connection and try again."
        }
        if (error is SSLException) {
            return "Secure connection to Gemini failed. Check your network and phone time."
        }
        if (error is SecurityException) {
            return "Android denied an operation. Check ReplyMate notification and background permissions."
        }
        val text = error.message.orEmpty()
        val code = Regex("\\bHTTP\\s+(\\d{3})\\b").find(text)?.groupValues?.get(1)?.toIntOrNull()
        if (code != null) return when (code) {
            400 -> "Gemini HTTP 400: Invalid API request. Check model and request settings."
            401, 403 -> "Gemini HTTP $code: API key is invalid, restricted, or not authorized for this project."
            402 -> "Gemini HTTP 402: Billing or credits are required for this API request."
            404 -> "Gemini HTTP 404: The selected Gemini model is not available to this API key."
            408, 500, 502, 503, 504 -> "Gemini HTTP $code: Temporary server issue. Retry after a short wait."
            429 -> "Gemini HTTP 429: Usage quota or rate limit reached. Check your Google AI Studio limits."
            else -> "Gemini HTTP $code: The API rejected the request. Check model access and quota."
        }
        return when {
            text.contains("produced no reply", true) ||
            text.contains("produced no text", true) ->
                "Gemini returned an empty reply. Test the AI again with a simple message."
            text.contains("repeated the incoming", true) ->
                "Gemini repeated the incoming text twice, so ReplyMate blocked the unsafe reply."
            text.contains("no compatible reply action", true) ||
            text.contains("notification reply action", true) ->
                "WhatsApp did not provide a working notification Reply action."
            else -> "Unexpected reply processing error. Open Settings and run the Gemini test."
        }
    }
}
