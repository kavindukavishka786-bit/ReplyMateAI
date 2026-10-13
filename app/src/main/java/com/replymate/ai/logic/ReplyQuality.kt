package com.replymate.ai.logic

/** Pure reply validation. Do not auto-send if a model simply mirrors the sender. */
object ReplyQuality {
    private fun normalized(value: String): String = value
        .lowercase()
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")

    fun isEcho(incoming: String, candidate: String): Boolean {
        val source = normalized(incoming)
        val reply = normalized(candidate)
        if (source.isBlank() || reply.isBlank()) return true
        if (source == reply) return true
        // Rephrased transcript-style responses are not conversations.
        val prefixes = listOf("you said ", "your message is ", "incoming message ", "they said ", "message received ")
        if (prefixes.any { reply == it + source }) return true
        return source.length >= 20 && reply.length >= 20 &&
            (reply == "the message says $source" || reply == "you wrote $source")
    }
}
