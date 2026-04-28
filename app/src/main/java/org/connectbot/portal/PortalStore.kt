package org.connectbot.portal

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.spec.MGF1ParameterSpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
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

    fun loadVaultSecret(): String? {
        val encrypted = prefs.getString("vault_secret", null) ?: return null
        return decrypt(encrypted)
    }

    fun saveVaultSecret(secret: String) {
        require(secret.isNotBlank()) { "Vault secret is required" }
        prefs.edit().putString("vault_secret", encrypt(secret.trim())).apply()
    }

    fun clearVaultSecret() {
        prefs.edit().remove("vault_secret").apply()
    }

    fun loadVaultEnrollmentId(): String? =
        prefs.getString("vault_enrollment_id", null)

    fun saveVaultEnrollmentId(id: String) {
        prefs.edit().putString("vault_enrollment_id", id).apply()
    }

    fun clearVaultEnrollmentId() {
        prefs.edit().remove("vault_enrollment_id").apply()
    }

    fun clearVaultEnrollmentKey() {
        val keyStore = keyStore()
        if (keyStore.containsAlias(VAULT_ENROLLMENT_KEY_ALIAS)) {
            keyStore.deleteEntry(VAULT_ENROLLMENT_KEY_ALIAS)
        }
    }

    fun vaultEnrollmentPublicKeyBase64(): String {
        ensureVaultEnrollmentKey()
        val certificate = keyStore().getCertificate(VAULT_ENROLLMENT_KEY_ALIAS)
            ?: throw IllegalStateException("Vault enrollment key was not created")
        return Base64.encodeToString(certificate.publicKey.encoded, Base64.NO_WRAP)
    }

    fun decryptVaultEnrollmentSecret(encryptedSecretBase64: String): String {
        val entry = keyStore().getEntry(VAULT_ENROLLMENT_KEY_ALIAS, null) as? KeyStore.PrivateKeyEntry
            ?: throw IllegalStateException("Vault enrollment key was not created")
        val cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            entry.privateKey as PrivateKey,
            OAEPParameterSpec(
                "SHA-256",
                "MGF1",
                MGF1ParameterSpec.SHA1,
                PSource.PSpecified.DEFAULT,
            ),
        )
        val plaintext = cipher.doFinal(Base64.decode(encryptedSecretBase64, Base64.NO_WRAP))
        return String(plaintext, Charsets.UTF_8)
    }

    fun loadOrCreateVaultSecret(vault: HubVaultConfig): String {
        loadVaultSecret()?.let { return it }
        require(!vault.hasItems) {
            "Portal vault is locked because no vault secret is stored on this Android device"
        }
        val secret = PortalVaultCrypto.newVaultSecret()
        saveVaultSecret(secret)
        return secret
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
        val keyStore = keyStore()
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

    private fun ensureVaultEnrollmentKey() {
        if (keyStore().containsAlias(VAULT_ENROLLMENT_KEY_ALIAS)) return
        val generator = KeyPairGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_RSA,
            "AndroidKeyStore",
        )
        generator.initialize(
            KeyGenParameterSpec.Builder(
                VAULT_ENROLLMENT_KEY_ALIAS,
                KeyProperties.PURPOSE_DECRYPT,
            )
                .setKeySize(2048)
                .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA1)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP)
                .build(),
        )
        generator.generateKeyPair()
    }

    private fun keyStore(): KeyStore =
        KeyStore.getInstance("AndroidKeyStore").also { it.load(null) }

    companion object {
        private const val KEY_ALIAS = "portal_hub_tokens"
        private const val VAULT_ENROLLMENT_KEY_ALIAS = "portal_vault_enrollment_rsa"
    }
}
