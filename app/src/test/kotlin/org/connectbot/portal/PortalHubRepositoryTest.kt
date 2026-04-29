package org.connectbot.portal

import android.net.Uri
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PortalHubRepositoryTest {
    @Test
    fun normalizeAddsDefaultHttpsAndHubPort() {
        assertThat(PortalHubUrlNormalizer.normalize("hub.example.com"))
            .isEqualTo("https://hub.example.com:8080")
    }

    @Test
    fun normalizePreservesExplicitSchemeAndRemovesTrailingSlash() {
        assertThat(PortalHubUrlNormalizer.normalize("https://hub.example.com/"))
            .isEqualTo("https://hub.example.com")
    }

    @Test
    fun normalizeKeepsExplicitHostPort() {
        assertThat(PortalHubUrlNormalizer.normalize("hub.example.com:9443"))
            .isEqualTo("https://hub.example.com:9443")
    }

    @Test
    fun normalizeUsesHttpForLocalhost() {
        assertThat(PortalHubUrlNormalizer.normalize("localhost"))
            .isEqualTo("http://localhost:8080")
        assertThat(PortalHubUrlNormalizer.normalize("127.0.0.1"))
            .isEqualTo("http://127.0.0.1:8080")
    }

    @Test
    fun normalizeRejectsBlankUrl() {
        assertThatThrownBy { PortalHubUrlNormalizer.normalize("   ") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("Portal Hub URL is required")
    }

    @Test
    fun parsesAndroidPairingHubUrl() {
        val uri = Uri.parse(
            "com.digitalpals.portal.android:/pair?hub_url=https%3A%2F%2Fhub.example.com%3A8080&pairing_id=pair-123",
        )

        assertThat(PortalAndroidPairing.hubUrlFrom(uri))
            .isEqualTo("https://hub.example.com:8080")
        assertThat(PortalAndroidPairing.from(uri)?.pairingId).isEqualTo("pair-123")
    }

    @Test
    fun ignoresNonPairingAndroidLinks() {
        val uri = Uri.parse("com.digitalpals.portal.android:/oauth2redirect?code=abc")

        assertThat(PortalAndroidPairing.hubUrlFrom(uri)).isNull()
    }

    @Test
    fun vaultEnrollmentCreatePayloadIncludesPairingId() {
        val payload = HubClient.vaultEnrollmentCreateJson(
            deviceName = "Pixel",
            publicKeyDerBase64 = "cHVibGlj",
            pairingId = "pair-123",
        )

        assertThat(payload.getString("device_name")).isEqualTo("Pixel")
        assertThat(payload.getString("public_key_algorithm")).isEqualTo("RSA-OAEP-SHA256")
        assertThat(payload.getString("public_key_der_base64")).isEqualTo("cHVibGlj")
        assertThat(payload.getString("pairing_id")).isEqualTo("pair-123")
    }

    @Test
    fun parsesVaultEnrollmentEventLine() {
        val enrollment = HubClient.vaultEnrollmentFromSseLine(
            """
            data: {"type":"vault_enrollment","enrollment":{"id":"00000000-0000-0000-0000-000000000001","device_name":"Pixel","status":"revoked","encrypted_secret_base64":null,"pairing_id":"pair-123","created_at":"2026-04-29T12:00:00Z","updated_at":"2026-04-29T12:05:00Z","approved_at":"2026-04-29T12:01:00Z","revoked_at":"2026-04-29T12:05:00Z"}}
            """.trimIndent(),
        )

        assertThat(enrollment?.id).isEqualTo("00000000-0000-0000-0000-000000000001")
        assertThat(enrollment?.status).isEqualTo("revoked")
        assertThat(enrollment?.pairingId).isEqualTo("pair-123")
        assertThat(enrollment?.revokedAt).isEqualTo("2026-04-29T12:05:00Z")
    }

    @Test
    fun parsesCachedSyncSnapshot() {
        val sync = """
            {
              "services": {
                "hosts": {
                  "revision": "3",
                  "payload": {
                    "hosts": [
                      {
                        "id": "host-1",
                        "name": "prod",
                        "hostname": "prod.example.com",
                        "port": 2222,
                        "username": "deploy",
                        "protocol": "ssh",
                        "portal_hub_enabled": true,
                        "auth": {
                          "vault_key_id": "key-1"
                        }
                      }
                    ]
                  },
                  "tombstones": ["old-host"]
                }
              }
            }
        """.trimIndent().toHubSyncState()

        assertThat(sync.services["hosts"]?.revision).isEqualTo("3")
        assertThat(sync.services["hosts"]?.tombstones).isEqualTo(listOf("old-host"))
        val host = sync.hosts.single()
        assertThat(host.id).isEqualTo("host-1")
        assertThat(host.name).isEqualTo("prod")
        assertThat(host.hostname).isEqualTo("prod.example.com")
        assertThat(host.port).isEqualTo(2222)
        assertThat(host.targetUser).isEqualTo("deploy")
        assertThat(host.connectable).isTrue()
        assertThat(host.vaultKeyId).isEqualTo("key-1")
    }

    @Test
    fun hostUpdatePreservesUnknownFields() {
        val sync = """
            {
              "services": {
                "hosts": {
                  "revision": "3",
                  "payload": {
                    "profile": "default",
                    "hosts": [
                      {
                        "id": "host-1",
                        "name": "prod",
                        "hostname": "prod.example.com",
                        "port": 2222,
                        "username": "deploy",
                        "protocol": "ssh",
                        "portal_hub_enabled": true,
                        "custom_desktop_field": "keep-me",
                        "auth": {
                          "vault_key_id": "key-1"
                        }
                      }
                    ]
                  },
                  "tombstones": ["old-host"]
                }
              }
            }
        """.trimIndent().toHubSyncState()

        val payload = sync.updateHost(
            id = "host-1",
            name = "staging",
            hostname = "staging.example.com",
            port = 2200,
            username = "admin",
            portalHubEnabled = false,
            vaultKeyId = "key-2",
        )
        val updatedHost = payload.getJSONArray("hosts").getJSONObject(0)

        assertThat(payload.getString("profile")).isEqualTo("default")
        assertThat(updatedHost.getString("name")).isEqualTo("staging")
        assertThat(updatedHost.getString("hostname")).isEqualTo("staging.example.com")
        assertThat(updatedHost.getInt("port")).isEqualTo(2200)
        assertThat(updatedHost.getString("username")).isEqualTo("admin")
        assertThat(updatedHost.getBoolean("portal_hub_enabled")).isFalse()
        assertThat(updatedHost.getString("protocol")).isEqualTo("ssh")
        assertThat(updatedHost.getString("custom_desktop_field")).isEqualTo("keep-me")
        assertThat(updatedHost.getJSONObject("auth").getString("type")).isEqualTo("public_key")
        assertThat(updatedHost.getJSONObject("auth").getString("vault_key_id")).isEqualTo("key-2")
    }
}
