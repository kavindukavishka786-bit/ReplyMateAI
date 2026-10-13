package com.replymate.ai.logic

/** Recovery must never override an explicit Stop, OFF, or revoked permission. */
object BackgroundPolicy {
    fun mayRecover(requested: Boolean, stopped: Boolean, enabled: Boolean,
                   listenerAccess: Boolean, notifications: Boolean, hasKey: Boolean): Boolean =
        requested && !stopped && enabled && listenerAccess && notifications && hasKey
}
