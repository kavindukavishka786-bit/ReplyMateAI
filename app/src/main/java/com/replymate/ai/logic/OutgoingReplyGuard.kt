package com.replymate.ai.logic

import android.content.Context
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import org.json.JSONArray
import org.json.JSONObject

/** Persist only salted fingerprints, before RemoteInput dispatch; never reply text. */
class OutgoingReplyGuard(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("outgoing_replies", Context.MODE_PRIVATE)
    companion object {
        private val lock = Any()
        private const val TTL_MS = 10 * 60_000L
        private const val MAX = 128
    }
    private fun hash(chat: String, body: String): String {
        val saved = prefs.getString("salt", null)
        val salt = if (saved == null) ByteArray(32).also {
            SecureRandom().nextBytes(it)
            check(prefs.edit().putString("salt", Base64.encodeToString(it, Base64.NO_WRAP)).commit())
        } else Base64.decode(saved, Base64.NO_WRAP)
        require(salt.size == 32)
        val identity = JSONArray().put(chat.trim()).put(IncomingReplySafety.normalizedBody(body)).toString()
        return MessageDigest.getInstance("SHA-256").digest(salt + identity.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
    private fun rows(now: Long): List<Pair<String, Long>> {
        val input = JSONArray(prefs.getString("entries", "[]"))
        return (0 until input.length()).map {
            val row = input.getJSONObject(it)
            val at = row.getLong("at")
            check(now >= at) // Clock rollback cannot erase a reservation and cause a replay.
            row.getString("hash") to at
        }.filter { now - it.second <= TTL_MS }
    }
    fun markOutgoing(chat: String, body: String, now: Long = System.currentTimeMillis()): Boolean = synchronized(lock) {
        try {
            val digest = hash(chat, body)
            val records = rows(now).filterNot { it.first == digest }.toMutableList()
            if (records.size >= MAX) return@synchronized false
            records.add(digest to now)
            val output = JSONArray()
            records.forEach { (h, at) -> output.put(JSONObject().put("hash", h).put("at", at)) }
            prefs.edit().putString("entries", output.toString()).commit()
        } catch (_: Exception) { false }
    }
    fun isOutgoing(chat: String, body: String, now: Long = System.currentTimeMillis()): Boolean = synchronized(lock) {
        try { val digest = hash(chat, body); rows(now).any { it.first == digest } }
        catch (_: Exception) { true } // Unknown storage state must never authorize a send.
    }
}
