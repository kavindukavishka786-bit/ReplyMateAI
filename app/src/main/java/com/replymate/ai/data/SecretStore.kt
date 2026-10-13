package com.replymate.ai.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** The user's key is encrypted at rest with a non-exportable Android Keystore key.
 * It still exists in app memory during API calls: avoid using root/compromised devices.
 */
class SecretStore(context: Context) {
    private val prefs = context.getSharedPreferences("replymate_secrets", Context.MODE_PRIVATE)
    private val alias = "ReplyMateGeminiKeyV1"

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setKeySize(256)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    fun save(key: String): Boolean = runCatching {
        val raw = key.trim()
        if (raw.isBlank()) { prefs.edit().remove("gemini").apply(); return@runCatching }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(raw.toByteArray(Charsets.UTF_8))
        val blob = cipher.iv + encrypted
        prefs.edit().putString("gemini", Base64.encodeToString(blob, Base64.NO_WRAP)).apply()
    }.isSuccess

    fun read(): String? = runCatching {
        val encoded = prefs.getString("gemini", null) ?: return@runCatching null
        val data = Base64.decode(encoded, Base64.NO_WRAP)
        if (data.size < 29) return@runCatching null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, data.copyOfRange(0, 12)))
        String(cipher.doFinal(data.copyOfRange(12, data.size)), Charsets.UTF_8)
    }.getOrNull()

    fun hasKey() = !read().isNullOrBlank()
    fun clear() = prefs.edit().remove("gemini").apply()
}
