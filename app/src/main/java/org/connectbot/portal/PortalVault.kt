package org.connectbot.portal

import android.util.Base64
import com.google.crypto.tink.aead.internal.InsecureNonceXChaCha20Poly1305
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

data class HubVaultConfig(
    val keys: List<VaultKey> = emptyList(),
    val secrets: List<VaultSecret> = emptyList(),
) {
    val hasItems: Boolean
        get() = keys.isNotEmpty() || secrets.isNotEmpty()

    fun findKey(id: String?): VaultKey? = keys.firstOrNull { it.id == id }

    fun upsertKey(key: VaultKey): HubVaultConfig =
        copy(keys = keys.filterNot { it.id == key.id } + key)

    fun renameKey(id: String, name: String): HubVaultConfig =
        copy(
            keys = keys.map {
                if (it.id == id) it.copy(name = name, updatedAt = nowIso()) else it
            },
        )

    fun deleteKey(id: String): HubVaultConfig = copy(keys = keys.filterNot { it.id == id })

    fun toJson(): JSONObject = JSONObject()
        .put("keys", JSONArray().also { array -> keys.forEach { array.put(it.toJson()) } })
        .also { root ->
            if (secrets.isNotEmpty()) {
                root.put("secrets", JSONArray().also { array -> secrets.forEach { array.put(it.toJson()) } })
            }
        }

    companion object {
        fun fromJson(json: JSONObject?): HubVaultConfig {
            if (json == null) return HubVaultConfig()
            val keys = json.optJSONArray("keys").toObjectList().map { VaultKey.fromJson(it) }
            val secrets = json.optJSONArray("secrets").toObjectList().map { VaultSecret.fromJson(it) }
            return HubVaultConfig(keys = keys, secrets = secrets)
        }
    }
}

data class VaultKey(
    val id: String,
    val name: String,
    val publicKey: String?,
    val fingerprint: String?,
    val algorithm: String?,
    val createdAt: String,
    val updatedAt: String,
    val encryption: VaultEncryption,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("created_at", createdAt)
        .put("updated_at", updatedAt)
        .put("encryption", encryption.toJson())
        .putIfPresent("public_key", publicKey)
        .putIfPresent("fingerprint", fingerprint)
        .putIfPresent("algorithm", algorithm)

    companion object {
        fun fromJson(json: JSONObject) = VaultKey(
            id = json.optString("id"),
            name = json.optString("name", "SSH key"),
            publicKey = json.optStringOrNull("public_key"),
            fingerprint = json.optStringOrNull("fingerprint"),
            algorithm = json.optStringOrNull("algorithm"),
            createdAt = json.optString("created_at"),
            updatedAt = json.optString("updated_at"),
            encryption = VaultEncryption.fromJson(json.getJSONObject("encryption")),
        )
    }
}

data class VaultSecret(
    val id: String,
    val name: String,
    val kind: String,
    val createdAt: String,
    val updatedAt: String,
    val encryption: VaultEncryption,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("kind", kind)
        .put("created_at", createdAt)
        .put("updated_at", updatedAt)
        .put("encryption", encryption.toJson())

    companion object {
        fun fromJson(json: JSONObject) = VaultSecret(
            id = json.optString("id"),
            name = json.optString("name"),
            kind = json.optString("kind"),
            createdAt = json.optString("created_at"),
            updatedAt = json.optString("updated_at"),
            encryption = VaultEncryption.fromJson(json.getJSONObject("encryption")),
        )
    }
}

data class VaultEncryption(
    val kdf: VaultKdf,
    val saltBase64: String,
    val cipher: String,
    val nonceBase64: String,
    val ciphertextBase64: String,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("kdf", kdf.toJson())
        .put("salt_base64", saltBase64)
        .put("cipher", cipher)
        .put("nonce_base64", nonceBase64)
        .put("ciphertext_base64", ciphertextBase64)

    companion object {
        fun fromJson(json: JSONObject) = VaultEncryption(
            kdf = VaultKdf.fromJson(json.getJSONObject("kdf")),
            saltBase64 = json.getString("salt_base64"),
            cipher = json.getString("cipher"),
            nonceBase64 = json.getString("nonce_base64"),
            ciphertextBase64 = json.getString("ciphertext_base64"),
        )
    }
}

data class VaultKdf(
    val algorithm: String,
    val memoryKib: Int,
    val iterations: Int,
    val parallelism: Int,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("algorithm", algorithm)
        .put("memory_kib", memoryKib)
        .put("iterations", iterations)
        .put("parallelism", parallelism)

    companion object {
        fun fromJson(json: JSONObject) = VaultKdf(
            algorithm = json.getString("algorithm"),
            memoryKib = json.getInt("memory_kib"),
            iterations = json.getInt("iterations"),
            parallelism = json.getInt("parallelism"),
        )
    }
}

object PortalVaultCrypto {
    private const val CIPHER = "XChaCha20Poly1305"
    private const val KDF = "Argon2id"
    private const val KEY_LEN = 32
    private const val SALT_LEN = 16
    private const val NONCE_LEN = 24
    private const val DEVICE_SECRET_LEN = 32

    fun newVaultSecret(): String {
        val secret = ByteArray(DEVICE_SECRET_LEN)
        SecureRandom().nextBytes(secret)
        return encodeBase64(secret)
    }

    fun encryptPrivateKey(name: String, privateKey: String, vaultSecret: String): VaultKey {
        val now = nowIso()
        return VaultKey(
            id = UUID.randomUUID().toString(),
            name = name,
            publicKey = null,
            fingerprint = null,
            algorithm = detectPrivateKeyAlgorithm(privateKey),
            createdAt = now,
            updatedAt = now,
            encryption = encryptBytes(privateKey.toByteArray(Charsets.UTF_8), vaultSecret),
        )
    }

    fun decryptPrivateKey(key: VaultKey, vaultSecret: String): String =
        decryptEncryption(key.encryption, vaultSecret)

    fun decryptSecret(secret: VaultSecret, vaultSecret: String): String =
        decryptEncryption(secret.encryption, vaultSecret)

    private fun encryptBytes(plaintext: ByteArray, vaultSecret: String): VaultEncryption {
        val salt = ByteArray(SALT_LEN)
        val nonce = ByteArray(NONCE_LEN)
        SecureRandom().nextBytes(salt)
        SecureRandom().nextBytes(nonce)
        val kdf = defaultKdf()
        val key = deriveKey(vaultSecret, salt, kdf)
        val ciphertext = InsecureNonceXChaCha20Poly1305(key).encrypt(nonce, plaintext, ByteArray(0))
        return VaultEncryption(
            kdf = kdf,
            saltBase64 = encodeBase64(salt),
            cipher = CIPHER,
            nonceBase64 = encodeBase64(nonce),
            ciphertextBase64 = encodeBase64(ciphertext),
        )
    }

    private fun decryptEncryption(encryption: VaultEncryption, vaultSecret: String): String {
        require(encryption.cipher == CIPHER) { "unsupported vault cipher: ${encryption.cipher}" }
        val nonce = decodeBase64(encryption.nonceBase64)
        require(nonce.size == NONCE_LEN) { "invalid vault nonce length" }
        val key = deriveKey(vaultSecret, decodeBase64(encryption.saltBase64), encryption.kdf)
        val plaintext = InsecureNonceXChaCha20Poly1305(key)
            .decrypt(nonce, decodeBase64(encryption.ciphertextBase64), ByteArray(0))
        return plaintext.toString(Charsets.UTF_8)
    }

    private fun deriveKey(vaultSecret: String, salt: ByteArray, kdf: VaultKdf): ByteArray {
        require(kdf.algorithm == KDF) { "unsupported vault KDF: ${kdf.algorithm}" }
        val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withMemoryAsKB(kdf.memoryKib)
            .withIterations(kdf.iterations)
            .withParallelism(kdf.parallelism)
            .withSalt(salt)
            .build()
        val output = ByteArray(KEY_LEN)
        Argon2BytesGenerator().apply { init(params) }
            .generateBytes(vaultSecret.toByteArray(Charsets.UTF_8), output)
        return output
    }

    private fun defaultKdf() = VaultKdf(
        algorithm = KDF,
        memoryKib = 64 * 1024,
        iterations = 3,
        parallelism = 1,
    )

    private fun detectPrivateKeyAlgorithm(privateKey: String): String? {
        val firstLine = privateKey.lineSequence().firstOrNull()?.trim().orEmpty()
        return when {
            "OPENSSH" in firstLine -> "OpenSSH"
            "RSA" in firstLine -> "RSA"
            "DSA" in firstLine -> "DSA"
            "EC" in firstLine -> "EC"
            firstLine.startsWith("-----BEGIN") -> "PEM"
            else -> null
        }
    }
}

private fun JSONArray?.toObjectList(): List<JSONObject> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { optJSONObject(it) }
}

private fun JSONObject.optStringOrNull(name: String): String? =
    if (has(name) && !isNull(name)) optString(name).ifBlank { null } else null

private fun JSONObject.putIfPresent(name: String, value: String?): JSONObject =
    if (value == null) this else put(name, value)

private fun encodeBase64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

private fun decodeBase64(value: String): ByteArray = Base64.decode(value, Base64.DEFAULT)

fun nowIso(): String {
    val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
    format.timeZone = TimeZone.getTimeZone("UTC")
    return format.format(Date())
}
