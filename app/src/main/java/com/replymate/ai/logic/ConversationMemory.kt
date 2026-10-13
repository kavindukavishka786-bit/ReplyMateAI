package com.replymate.ai.logic

/** Short-lived, in-memory chat context. No WhatsApp message is written to storage. */
data class ChatTurn(val incoming: String, val reply: String)

object ConversationMemory {
    private data class Entry(val turn: ChatTurn, val time: Long)
    private val chats = mutableMapOf<String, MutableList<Entry>>()
    private const val MAX_TURNS = 4
    private const val TTL_MS = 2 * 60 * 60 * 1000L

    @Synchronized fun recent(sender: String, now: Long = System.currentTimeMillis()): List<ChatTurn> {
        chats.entries.removeAll { (_, turns) ->
            turns.removeAll { now - it.time > TTL_MS }
            turns.isEmpty()
        }
        return chats[sender]?.map { it.turn }.orEmpty()
    }

    /** Only remember replies actually delivered through the notification action. */
    @Synchronized fun record(
        sender: String, incoming: String, reply: String, now: Long = System.currentTimeMillis()
    ) {
        recent(sender, now)
        val list = chats.getOrPut(sender) { mutableListOf() }
        list.add(Entry(ChatTurn(incoming.take(500), reply.take(500)), now))
        while (list.size > MAX_TURNS) list.removeAt(0)
        // Bound memory usage even if the owner chats with many contacts.
        if (chats.size > 40) {
            val oldest = chats.minByOrNull { (_, entries) -> entries.lastOrNull()?.time ?: Long.MIN_VALUE }
            if (oldest != null) chats.remove(oldest.key)
        }
    }
}
