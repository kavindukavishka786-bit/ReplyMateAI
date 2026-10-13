package com.replymate.ai.logic

import com.replymate.ai.data.AppSettings

/** Shared send-time policy for automatic and manually approved replies. */
object SendSafety {
    fun permits(settings: AppSettings, sender: String, group: Boolean,
                incoming: String, reply: String): Boolean =
        settings.enabled && (!group || settings.includeGroups) &&
            settings.sent < settings.dailyLimit && reply.isNotBlank() &&
            reply.length <= 700 && !ReplyQuality.isEcho(incoming, reply) &&
            ContactRules.isPermitted(sender, settings.allowed, settings.blocked, settings.allowAll)
}
