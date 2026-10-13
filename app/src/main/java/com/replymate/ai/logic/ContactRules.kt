package com.replymate.ai.logic

/** Avoid fuzzy matches: a partial name must never accidentally match a different contact.
 * This only verifies display-name equality, not the WhatsApp account's true identity.
 */
object ContactRules {
    fun isPermitted(displayName: String, allowed: String, blocked: String, allowAll: Boolean): Boolean {
        val name = displayName.trim()
        if (name.isEmpty()) return false
        fun matches(lines: String) = lines.lineSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .any { it.equals(name, ignoreCase = true) }
        return !matches(blocked) && (allowAll || matches(allowed))
    }
}
