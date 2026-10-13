package com.replymate.ai.logic

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import kotlinx.coroutines.sync.Mutex

object ReplyDispatch { val mutex = Mutex() }

object ReplyPermissions {
    fun listenerAllowed(context: Context): Boolean {
        val expected = ComponentName(context, WhatsAppListener::class.java)
        val enabled = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners").orEmpty()
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == expected }
    }
}
