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
 * Exact and timestamp-changed notifications are blocked for 15 minutes.
 * WhatsApp exposes no stable message ID, so identical text from the same sender
 * during this window is intentionally treated as ambiguous and skipped.
 * Reservations are synchronously committed on IO BEFORE Gemini or a send.
 *
 * Persist ONLY salted digests and receipt times, never message text/contact names.
 */
class IncomingReceiptGuard internal constructor(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("incoming_receipts", Context.MODE_PRIVATE)
    private data class Item(val exact: String, val loose: String, val at: Long)
    private val entries = ArrayList<Item>(256)
    private val key: ByteArray
    private var saltPersisted = true

    companion object {
        private const val EXACT_TTL_MS = ReceiptPolicy.RETAIN_MS
        private const val MAX_RECEIPTS = 1024
        @Volatile private var instance: IncomingReceiptGuard? = null

        fun getInstance(context: Context): IncomingReceiptGuard = instance ?: synchronized(this) {
            instance ?: IncomingReceiptGuard(context.applicationContext).also { instance = it }
        }
    }

    init {
        val existing = prefs.getString("salt", null)
        key = if (existing != null) {
            Base64.decode(existing, Base64.NO_WRAP)
        } else {
            ByteArray(32).also { SecureRandom().nextBytes(it) }.also {
                saltPersisted = prefs.edit().putString("salt", Base64.encodeToString(it, Base64.NO_WRAP)).commit()
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
                if (exact.isNotBlank() && loose.isNotBlank() && now - at in 0L..EXACT_TTL_MS) {
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
        if (ReceiptPolicy.isDuplicate(entries.map { Triple(it.exact, it.loose, it.at) },
                exact, loose, now)) return false
        // Never evict a live reservation: a full guard fails closed.
        if (entries.size >= MAX_RECEIPTS) return false
        entries.add(Item(exact, loose, now))
        return persist()
    }

    @Synchronized
    fun markOutgoing(chat: String, message: String, now: Long = System.currentTimeMillis()): Boolean {
        entries.removeAll { now - it.at !in 0L..EXACT_TTL_MS }
        val hash = digest("outgoing|" + chat.trim() + "|" + IncomingReplySafety.normalizedBody(message))
        entries.removeAll { it.exact == hash }
        if (entries.size >= MAX_RECEIPTS) return false
        entries.add(Item(hash, hash, now))
        return persist()
    }

    @Synchronized
    fun isOutgoing(chat: String, message: String, now: Long = System.currentTimeMillis()): Boolean {
        val hash = digest("outgoing|" + chat.trim() + "|" + IncomingReplySafety.normalizedBody(message))
        return entries.any { it.exact == hash && now - it.at in 0L..10 * 60_000L }
    }

    private fun persist(): Boolean {
        val arr = JSONArray()
        entries.forEach { arr.put(JSONObject()
            .put("exact", it.exact).put("loose", it.loose).put("at", it.at)) }
        // A disk failure must not permit a send that could repeat after a restart.
        return saltPersisted && prefs.edit().putString("receipts", arr.toString()).commit()
    }
}
