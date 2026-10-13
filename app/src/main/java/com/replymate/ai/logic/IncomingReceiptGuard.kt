package com.replymate.ai.logic

import android.content.Context
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import org.json.JSONArray
import org.json.JSONObject

/**
 * Durable, process-wide at-most-once admission, before Gemini or direct reply.
 * Only salted digests and times are stored. Call on IO, never on the UI callback.
 * Timestamp aliases of suppressed refreshes are also saved. Identical sender+body
 * is coalesced for three minutes after its latest notification; deliberately repeated
 * identical messages in that window can be skipped. Different bodies proceed normally.
 */
class IncomingReceiptGuard(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("incoming_receipts", Context.MODE_PRIVATE)
    companion object {
        private val lock = Any()
        private const val EXACT_TTL_MS = 15 * 60_000L
        private const val MAX_RECEIPTS = 2048
    }

    fun accept(chat: String, author: String, timestamp: Long, message: String,
               now: Long = System.currentTimeMillis(),
               legacyChat: String = chat, legacyAuthor: String = author): Boolean = synchronized(lock) {
        try {
            val salt = prefs.getString("salt", null)
            val key = if (salt != null) Base64.decode(salt, Base64.NO_WRAP) else {
                val created = ByteArray(32).also { SecureRandom().nextBytes(it) }
                if (!prefs.edit().putString("salt", Base64.encodeToString(created, Base64.NO_WRAP)).commit())
                    return@synchronized false
                created
            }
            require(key.size == 32)
            fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
                .digest(key + value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
            // JSON encoding avoids ambiguous separators in names/body.
            val identity = JSONArray().put(chat.trim()).put(author.trim())
                .put(IncomingReplySafety.normalizedBody(message)).toString()
            val loose = digest(identity)
            val exact = digest(JSONArray().put(identity).put(timestamp).toString())
            val saved = JSONArray(prefs.getString("receipts", "[]") ?: "[]")
            val rows = ArrayList<Triple<String, String, Long>>()
            for (i in 0 until saved.length()) {
                val row = saved.getJSONObject(i)
                val at = row.getLong("at")
                // Keep future entries after a clock rollback: fail closed, don't replay.
                if (now - at <= EXACT_TTL_MS) rows.add(Triple(row.getString("exact"), row.getString("loose"), at))
            }
            val legacyHeader = legacyChat.trim() + "|" + legacyAuthor.trim() + "|"
            val legacyLoose = digest(legacyHeader + IncomingReplySafety.normalizedBody(message))
            val legacyExact = digest(legacyHeader + timestamp.toString() + "|" + IncomingReplySafety.normalizedBody(message))
            val duplicate = ReceiptPolicy.isDuplicate(rows, exact, loose, now) ||
                ReceiptPolicy.isDuplicate(rows, legacyExact, legacyLoose, now)
            val index = rows.indexOfFirst { it.first == exact }
            if (index >= 0) rows[index] = Triple(exact, loose, now) else {
                // Never evict a live receipt to make space for a possible duplicate.
                if (rows.size >= MAX_RECEIPTS) return@synchronized false
                rows.add(Triple(exact, loose, now))
            }
            val out = JSONArray()
            rows.forEach { (e, l, at) -> out.put(JSONObject().put("exact", e).put("loose", l).put("at", at)) }
            // Commit BEFORE admitting work. A killed process cannot lose an accepted receipt.
            prefs.edit().putString("receipts", out.toString()).commit() && !duplicate
        } catch (_: Exception) {
            // Corruption or unavailable storage must not cause a second send.
            false
        }
    }
}
