package org.connectbot.portal

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class PortalStore(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("portal_hub", Context.MODE_PRIVATE)

    var hubUrl: String
        get() = prefs.getString("hub_url", "") ?: ""
        set(value) {
            prefs.edit().putString("hub_url", value.trim().trimEnd('/')).apply()
        }

    fun loadTokens(): HubTokens? {
        val encrypted = prefs.getString("tokens", null) ?: return null
        val raw = decrypt(encrypted)
        val json = JSONObject(raw)
        return HubTokens(
            accessToken = json.getString("access_token"),
            refreshToken = json.getString("refresh_token"),
        )
    }

    fun saveTokens(tokens: HubTokens) {
        val json = JSONObject()
            .put("access_token", tokens.accessToken)
            .put("refresh_token", tokens.refreshToken)
        prefs.edit().putString("tokens", encrypt(json.toString())).apply()
    }

    fun clearTokens() {
        prefs.edit().remove("tokens").apply()
    }

    fun saveSyncSnapshot(raw: String) {
        prefs.edit().putString("sync_snapshot", raw).apply()
    }

    fun loadSyncSnapshot(): String? = prefs.getString("sync_snapshot", null)

    private fun encrypt(plainText: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        return listOf(cipher.iv, encrypted).joinToString(":") {
            Base64.encodeToString(it, Base64.NO_WRAP)
        }
    }

    private fun decrypt(encoded: String): String {
        val parts = encoded.split(":")
        require(parts.size == 2) { "invalid encrypted value" }
        val iv = Base64.decode(parts[0], Base64.NO_WRAP)
        val ciphertext = Base64.decode(parts[1], Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").also { it.load(null) }
        val existing = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
        if (existing != null) {
            return existing.secretKey
        }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        private const val KEY_ALIAS = "portal_hub_tokens"
    }
}
