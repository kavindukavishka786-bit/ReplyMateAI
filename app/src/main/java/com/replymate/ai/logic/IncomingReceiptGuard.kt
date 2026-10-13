package com.replymate.ai.logic

import android.content.Context
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import org.json.JSONArray
import org.json.JSONObject

/**
 * Deduplicates WhatsApp notification refreshes BEFORE generating/sending AI replies.
 *
 * Exact notifications are blocked for 15 minutes; changed Android timestamps with
 * identical sender+body are suppressed for only 12 seconds. That short coalescing
 * window avoids the common double-send from notification refreshes, but genuinely
 * repeated identical messages during it may be skipped.
 *
 * Persist ONLY salted digests and receipt times, never message text/contact names.
 */
class IncomingReceiptGuard(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("incoming_receipts", Context.MODE_PRIVATE)
    private data class Item(val exact: String, val loose: String, val at: Long)
    private val entries = ArrayList<Item>(256)
    private val key: ByteArray

    companion object {
        private const val EXACT_TTL_MS = 15 * 60_000L
        private const val REFRESH_TTL_MS = 12_000L
        private const val MAX_RECEIPTS = 256
    }

    init {
        val existing = prefs.getString("salt", null)
        key = if (existing != null) {
            Base64.decode(existing, Base64.NO_WRAP)
        } else {
            ByteArray(32).also { SecureRandom().nextBytes(it) }.also {
                prefs.edit().putString("salt", Base64.encodeToString(it, Base64.NO_WRAP)).apply()
            }
        }
        val saved = prefs.getString("receipts", "[]") ?: "[]"
        runCatching {
            val arr = JSONArray(saved)
            val now = System.currentTimeMillis()
            for (i in 0 until arr.length()) {
                val row = arr.optJSONObject(i) ?: continue
                val at = row.optLong("at", 0L)
                val exact = row.optString("exact", "")
                val loose = row.optString("loose", "")
                if (exact.isNotBlank() && loose.isNotBlank() && now - at in 0..EXACT_TTL_MS) {
                    entries.add(Item(exact, loose, at))
                }
            }
        }
        if (entries.size > MAX_RECEIPTS) entries.subList(0, entries.size - MAX_RECEIPTS).clear()
    }

    private fun digest(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest(key + value.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    @Synchronized
    fun accept(chat: String, author: String, timestamp: Long, message: String,
               now: Long = System.currentTimeMillis()): Boolean {
        entries.removeAll { now - it.at !in 0..EXACT_TTL_MS }
        val header = chat.trim() + "|" + author.trim() + "|"
        val normalized = IncomingReplySafety.normalizedBody(message)
        val loose = digest(header + normalized)
        val exact = digest(header + timestamp.toString() + "|" + normalized)
        if (entries.any { it.exact == exact ||
                (it.loose == loose && now - it.at in 0..REFRESH_TTL_MS) }) return false
        entries.add(Item(exact, loose, now))
        if (entries.size > MAX_RECEIPTS) entries.subList(0, entries.size - MAX_RECEIPTS).clear()
        val arr = JSONArray()
        entries.forEach { arr.put(JSONObject()
            .put("exact", it.exact).put("loose", it.loose).put("at", it.at)) }
        prefs.edit().putString("receipts", arr.toString()).apply()
        return true
    }
}
