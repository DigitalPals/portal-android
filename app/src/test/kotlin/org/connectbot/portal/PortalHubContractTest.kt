package org.connectbot.portal

import com.fasterxml.jackson.databind.ObjectMapper
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import org.assertj.core.api.Assertions.assertThat
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.exists
import kotlin.io.path.readText

@RunWith(RobolectricTestRunner::class)
class PortalHubContractTest {
    private val mapper = ObjectMapper()
    private val schemaFactory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)

    @Test
    fun apiInfoContractMatchesAndroidCapabilityParsing() {
        val instance = JSONObject()
            .put("api_version", 2)
            .put("version", "0.5.0-beta.13")
            .put("public_url", "https://portal-hub.example.ts.net")
            .put(
                "capabilities",
                JSONObject()
                    .put("sync_v2", true)
                    .put("sync_events", true)
                    .put("web_proxy", true)
                    .put("key_vault", true)
                    .put("vault_enrollment", true),
            )
            .put("ssh_port", 2222)
            .put("ssh_username", "portal-hub")

        assertContract("api-info-response", instance)
        val capabilities = instance.getJSONObject("capabilities")
        val info = HubInfo(
            apiVersion = instance.getInt("api_version"),
            version = instance.getString("version"),
            publicUrl = instance.getString("public_url"),
            webProxy = capabilities.getBoolean("web_proxy"),
            syncV2 = capabilities.getBoolean("sync_v2"),
            syncEvents = capabilities.getBoolean("sync_events"),
            keyVault = capabilities.getBoolean("key_vault"),
            vaultEnrollment = capabilities.getBoolean("vault_enrollment"),
        )

        assertThat(info.apiVersion).isEqualTo(2)
        assertThat(info.webProxy).isTrue()
        assertThat(info.syncV2).isTrue()
        assertThat(info.syncEvents).isTrue()
        assertThat(info.keyVault).isTrue()
        assertThat(info.vaultEnrollment).isTrue()
    }

    @Test
    fun syncV2ContractMatchesAndroidParser() {
        val instance = JSONObject()
            .put("api_version", 2)
            .put("generated_at", "2026-04-29T12:00:00Z")
            .put(
                "services",
                JSONObject()
                    .put(
                        "hosts",
                        JSONObject()
                            .put("revision", "rev-hosts")
                            .put(
                                "payload",
                                JSONObject()
                                    .put(
                                        "hosts",
                                        JSONArray()
                                            .put(
                                                JSONObject()
                                                    .put("id", "host-1")
                                                    .put("name", "prod")
                                                    .put("hostname", "prod.example.com")
                                                    .put("port", 22)
                                                    .put("username", "deploy")
                                                    .put("protocol", "ssh")
                                                    .put("portal_hub_enabled", true)
                                                    .put(
                                                        "auth",
                                                        JSONObject().put("vault_key_id", "key-1"),
                                                    ),
                                            ),
                                    )
                                    .put("groups", JSONArray()),
                            )
                            .put("tombstones", JSONArray()),
                    )
                    .put(
                        "vault",
                        JSONObject()
                            .put("revision", "rev-vault")
                            .put(
                                "payload",
                                JSONObject()
                                    .put("keys", JSONArray())
                                    .put("secrets", JSONArray()),
                            )
                            .put("tombstones", JSONArray()),
                    ),
            )

        assertContract("sync-v2-response", instance)
        val sync = instance.toHubSyncState()

        assertThat(sync.services["hosts"]?.revision).isEqualTo("rev-hosts")
        assertThat(sync.hosts.single().connectable).isTrue()
        assertThat(sync.hosts.single().vaultKeyId).isEqualTo("key-1")
        assertThat(sync.vaultKeyCount).isEqualTo(0)
    }

    @Test
    fun syncV2PutContractMatchesAndroidHostUpdateRequest() {
        val sync = """
            {
              "services": {
                "hosts": {
                  "revision": "rev-hosts",
                  "payload": {
                    "hosts": [
                      {
                        "id": "host-1",
                        "name": "prod",
                        "hostname": "prod.example.com",
                        "port": 22,
                        "username": "deploy",
                        "protocol": "ssh",
                        "portal_hub_enabled": true
                      }
                    ],
                    "groups": []
                  },
                  "tombstones": []
                }
              }
            }
        """.trimIndent().toHubSyncState()
        val payload = sync.updateHost(
            id = "host-1",
            name = "prod",
            hostname = "prod.example.com",
            port = 22,
            username = "deploy",
            portalHubEnabled = true,
            vaultKeyId = "key-1",
        )
        val request = JSONObject()
            .put(
                "services",
                JSONObject()
                    .put(
                        "hosts",
                        JSONObject()
                            .put("expected_revision", "rev-hosts")
                            .put("payload", payload)
                            .put("tombstones", JSONArray()),
                    ),
            )

        assertContract("sync-v2-put-request", request)
    }

    @Test
    fun terminalStartContractMatchesAndroidTarget() {
        val target = TerminalTarget(
            sessionId = "00000000-0000-0000-0000-000000000001",
            targetHost = "example.internal",
            targetPort = 22,
            targetUser = "john",
            cols = 120,
            rows = 30,
            privateKey = "-----BEGIN OPENSSH PRIVATE KEY-----\n...\n",
        )
        val request = JSONObject()
            .put("session_id", target.sessionId)
            .put("target_host", target.targetHost)
            .put("target_port", target.targetPort)
            .put("target_user", target.targetUser)
            .put("cols", target.cols)
            .put("rows", target.rows)
            .put("private_key", target.privateKey)

        assertContract("terminal-start-request", request)
    }

    @Test
    fun vaultEnrollmentContractMatchesAndroidParser() {
        val instance = JSONObject()
            .put("id", "00000000-0000-0000-0000-000000000001")
            .put("device_name", "Pixel")
            .put("public_key_algorithm", "RSA-OAEP-SHA256")
            .put("public_key_der_base64", "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8A")
            .put("status", "approved")
            .put("encrypted_secret_base64", "c2VjcmV0")
            .put("pairing_id", "00000000-0000-0000-0000-000000000010")
            .put("created_at", "2026-04-29T12:00:00Z")
            .put("updated_at", "2026-04-29T12:01:00Z")
            .put("approved_at", "2026-04-29T12:01:00Z")
            .put("revoked_at", JSONObject.NULL)

        assertContract("vault-enrollment-response", instance)
        val enrollment = VaultEnrollment.fromJson(instance)

        assertThat(enrollment.id).isEqualTo("00000000-0000-0000-0000-000000000001")
        assertThat(enrollment.status).isEqualTo("approved")
        assertThat(enrollment.encryptedSecretBase64).isEqualTo("c2VjcmV0")
        assertThat(enrollment.pairingId).isEqualTo("00000000-0000-0000-0000-000000000010")
    }

    private fun assertContract(schemaName: String, instance: JSONObject) {
        val schemaPath = schemaPath(schemaName)
        assumeTrue(
            "Set PORTAL_HUB_CONTRACT_DIR or check out portal-hub beside portal-android",
            schemaPath.exists(),
        )
        val schema = schemaFactory.getSchema(mapper.readTree(schemaPath.readText()))
        val errors = schema.validate(mapper.readTree(instance.toString()))

        assertThat(errors).isEmpty()
    }

    private fun schemaPath(schemaName: String): Path {
        val configured = System.getenv("PORTAL_HUB_CONTRACT_DIR")
            ?: System.getProperty("PORTAL_HUB_CONTRACT_DIR")
        if (!configured.isNullOrBlank()) {
            return Paths.get(configured).resolve("$schemaName.schema.json")
        }

        val cwd = Paths.get("").toAbsolutePath()
        val candidates = generateSequence(cwd) { it.parent }
            .flatMap { dir ->
                sequenceOf(
                    dir.resolve("../portal-hub/contracts/portal-hub/v2").normalize(),
                    dir.resolve("portal-hub/contracts/portal-hub/v2").normalize(),
                )
            }

        return candidates
            .map { it.resolve("$schemaName.schema.json") }
            .firstOrNull { it.exists() }
            ?: cwd.resolve("../portal-hub/contracts/portal-hub/v2/$schemaName.schema.json")
                .normalize()
    }
}
