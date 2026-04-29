package org.connectbot.portal

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class HubInfo(
    val apiVersion: Int,
    val version: String,
    val publicUrl: String,
    val webProxy: Boolean,
    val syncV2: Boolean,
    val syncEvents: Boolean,
    val keyVault: Boolean,
    val vaultEnrollment: Boolean,
)

data class HubTokens(
    val accessToken: String,
    val refreshToken: String,
)

data class PortalHost(
    val id: String,
    val name: String,
    val hostname: String,
    val port: Int,
    val username: String,
    val protocol: String,
    val portalHubEnabled: Boolean,
    val vaultKeyId: String?,
    val raw: JSONObject,
) {
    val connectable: Boolean
        get() = protocol == "ssh" && portalHubEnabled

    val targetUser: String
        get() = username.trim().ifEmpty { "root" }
}

data class PortalSnippet(
    val id: String,
    val name: String,
    val command: String,
)

data class HubSession(
    val sessionId: String,
    val targetHost: String,
    val targetPort: Int,
    val targetUser: String,
    val createdAt: String,
    val updatedAt: String,
)

data class HubServiceState(
    val revision: String,
    val payload: JSONObject,
    val tombstones: List<String>,
)

data class HubSyncState(
    val services: Map<String, HubServiceState>,
) {
    val hosts: List<PortalHost>
        get() {
            val payload = services["hosts"]?.payload ?: return emptyList()
            val hosts = payload.optJSONArray("hosts") ?: return emptyList()
            return (0 until hosts.length())
                .mapNotNull { hosts.optJSONObject(it) }
                .map { host ->
                    val auth = host.optJSONObject("auth")
                    PortalHost(
                        id = host.optString("id", UUID.randomUUID().toString()),
                        name = host.optString("name", host.optString("hostname")),
                        hostname = host.optString("hostname"),
                        port = host.optInt("port", 22),
                        username = host.optString("username"),
                        protocol = host.optString("protocol", "ssh"),
                        portalHubEnabled = host.optBoolean("portal_hub_enabled", false),
                        vaultKeyId = auth?.optString("vault_key_id")?.ifEmpty { null },
                        raw = host,
                    )
                }
        }

    val snippets: List<PortalSnippet>
        get() {
            val payload = services["snippets"]?.payload ?: return emptyList()
            val snippets = payload.optJSONArray("snippets") ?: return emptyList()
            return (0 until snippets.length())
                .mapNotNull { snippets.optJSONObject(it) }
                .map {
                    PortalSnippet(
                        id = it.optString("id"),
                        name = it.optString("name"),
                        command = it.optString("command"),
                    )
                }
        }

    val vaultKeyCount: Int
        get() = vault.keys.size

    val vaultSecretCount: Int
        get() = vault.secrets.size

    val vault: HubVaultConfig
        get() = HubVaultConfig.fromJson(services["vault"]?.payload)

    fun updateHost(
        id: String,
        name: String,
        hostname: String,
        port: Int,
        username: String,
        portalHubEnabled: Boolean,
        vaultKeyId: String?,
    ): JSONObject {
        val currentPayload = services["hosts"]?.payload ?: JSONObject()
        val updatedPayload = JSONObject(currentPayload.toString())
        val currentHosts = currentPayload.optJSONArray("hosts") ?: JSONArray()
        val updatedHosts = JSONArray()
        var found = false

        for (index in 0 until currentHosts.length()) {
            val currentHost = currentHosts.optJSONObject(index) ?: continue
            val updatedHost = JSONObject(currentHost.toString())
            if (updatedHost.optString("id") == id) {
                found = true
                updatedHost
                    .put("name", name)
                    .put("hostname", hostname)
                    .put("port", port)
                    .put("username", username)
                    .put("portal_hub_enabled", portalHubEnabled)
                val auth = updatedHost.optJSONObject("auth")?.let { JSONObject(it.toString()) }
                    ?: JSONObject()
                if (vaultKeyId.isNullOrBlank()) {
                    auth.remove("vault_key_id")
                    if (updatedHost.has("auth")) {
                        updatedHost.put("auth", auth)
                    }
                } else {
                    updatedHost.put(
                        "auth",
                        auth
                            .put("type", "public_key")
                            .put("vault_key_id", vaultKeyId),
                    )
                }
            }
            updatedHosts.put(updatedHost)
        }

        require(found) { "Host was not found in the synced Portal Hub hosts" }
        return updatedPayload.put("hosts", updatedHosts)
    }
}

fun JSONObject.objectMap(name: String): Map<String, JSONObject> {
    val root = optJSONObject(name) ?: return emptyMap()
    return root.keys().asSequence().associateWith { root.getJSONObject(it) }
}

fun JSONArray?.toStringList(): List<String> =
    if (this == null) emptyList() else (0 until length()).map { optString(it) }
