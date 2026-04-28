package org.connectbot.portal

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PortalVaultCryptoTest {
    @Test
    fun encryptsAndDecryptsPrivateKey() {
        val secret = PortalVaultCrypto.newVaultSecret()
        val privateKey = """
            -----BEGIN OPENSSH PRIVATE KEY-----
            example
            -----END OPENSSH PRIVATE KEY-----
        """.trimIndent() + "\n"

        val key = PortalVaultCrypto.encryptPrivateKey("default", privateKey, secret)

        assertThat(key.encryption.cipher).isEqualTo("XChaCha20Poly1305")
        assertThat(key.encryption.kdf.algorithm).isEqualTo("Argon2id")
        assertThat(key.encryption.ciphertextBase64).doesNotContain("OPENSSH")
        assertThat(PortalVaultCrypto.decryptPrivateKey(key, secret)).isEqualTo(privateKey)
    }

    @Test
    fun vaultJsonRoundTripsKeys() {
        val secret = PortalVaultCrypto.newVaultSecret()
        val key = PortalVaultCrypto.encryptPrivateKey("default", "private-key\n", secret)
        val vault = HubVaultConfig(keys = listOf(key))

        val decoded = HubVaultConfig.fromJson(vault.toJson())

        assertThat(decoded.keys).hasSize(1)
        assertThat(decoded.keys.first().id).isEqualTo(key.id)
        assertThat(decoded.keys.first().encryption.ciphertextBase64)
            .isEqualTo(key.encryption.ciphertextBase64)
    }
}
