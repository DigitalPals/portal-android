package org.connectbot.portal

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
}
