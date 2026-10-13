package com.replymate.ai.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate

private val Context.replyMateStore by preferencesDataStore(name = "replymate_settings")

enum class ReplyMode { MANUAL, AUTOMATIC }

data class AppSettings(
    val enabled: Boolean = false,
    val mode: ReplyMode = ReplyMode.MANUAL,
    val allowAll: Boolean = false,
    val allowed: String = "",
    val blocked: String = "",
    val includeGroups: Boolean = false,
    val personality: String = "Warm, brief, respectful. Reply in the sender's language. Identify as an AI assistant. Never make promises, share private facts or invent arrangements.",
    val dailyLimit: Int = 20,
    val cooldownMinutes: Int = 15,
    val generated: Int = 0,
    val sent: Int = 0,
    val skipped: Int = 0,
    val date: String = LocalDate.now().toString()
)

class SettingsRepository(private val context: Context) {
    private object Keys {
        val enabled = booleanPreferencesKey("enabled")
        val mode = stringPreferencesKey("mode")
        val allowAll = booleanPreferencesKey("allow_all")
        val allowed = stringPreferencesKey("allowed")
        val blocked = stringPreferencesKey("blocked")
        val groups = booleanPreferencesKey("groups")
        val personality = stringPreferencesKey("personality")
        val dailyLimit = intPreferencesKey("daily_limit")
        val cooldown = intPreferencesKey("cooldown")
        val generated = intPreferencesKey("generated")
        val sent = intPreferencesKey("sent")
        val skipped = intPreferencesKey("skipped")
        val date = stringPreferencesKey("date")
    }

    val settings: Flow<AppSettings> = context.replyMateStore.data.map { p ->
        val today = LocalDate.now().toString()
        AppSettings(
            enabled = p[Keys.enabled] ?: false,
            mode = if (p[Keys.mode] == "AUTO") ReplyMode.AUTOMATIC else ReplyMode.MANUAL,
            allowAll = p[Keys.allowAll] ?: false,
            allowed = p[Keys.allowed] ?: "",
            blocked = p[Keys.blocked] ?: "",
            includeGroups = p[Keys.groups] ?: false,
            personality = p[Keys.personality] ?: AppSettings().personality,
            dailyLimit = p[Keys.dailyLimit] ?: 20,
            cooldownMinutes = p[Keys.cooldown] ?: 15,
            generated = if (p[Keys.date] == today) p[Keys.generated] ?: 0 else 0,
            sent = if (p[Keys.date] == today) p[Keys.sent] ?: 0 else 0,
            skipped = if (p[Keys.date] == today) p[Keys.skipped] ?: 0 else 0,
            date = today
        )
    }

    suspend fun setEnabled(value: Boolean) = edit(Keys.enabled, value)
    suspend fun setMode(value: ReplyMode) = edit(Keys.mode, if (value == ReplyMode.AUTOMATIC) "AUTO" else "MANUAL")
    suspend fun setAllowAll(value: Boolean) = edit(Keys.allowAll, value)
    suspend fun setAllowed(value: String) = edit(Keys.allowed, value)
    suspend fun setBlocked(value: String) = edit(Keys.blocked, value)
    suspend fun setGroups(value: Boolean) = edit(Keys.groups, value)
    suspend fun setPersonality(value: String) = edit(Keys.personality, value)
    suspend fun setDailyLimit(value: Int) = edit(Keys.dailyLimit, value.coerceIn(1, 50))
    suspend fun setCooldown(value: Int) = edit(Keys.cooldown, value.coerceIn(1, 120))

    private suspend fun <T> edit(key: Preferences.Key<T>, value: T) {
        context.replyMateStore.edit { it[key] = value }
    }

    suspend fun countGenerated() = increase(Keys.generated)
    suspend fun countSent() = increase(Keys.sent)
    suspend fun countSkipped() = increase(Keys.skipped)

    private suspend fun increase(key: Preferences.Key<Int>) {
        context.replyMateStore.edit { p ->
            val today = LocalDate.now().toString()
            if (p[Keys.date] != today) {
                p[Keys.date] = today
                p[Keys.generated] = 0
                p[Keys.sent] = 0
                p[Keys.skipped] = 0
            }
            p[key] = (p[key] ?: 0) + 1
        }
    }
}
