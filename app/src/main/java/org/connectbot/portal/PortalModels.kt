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
    val tombstones: Any,
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
}

fun JSONObject.objectMap(name: String): Map<String, JSONObject> {
    val root = optJSONObject(name) ?: return emptyMap()
    return root.keys().asSequence().associateWith { root.getJSONObject(it) }
}

fun JSONArray.toStringList(): List<String> = (0 until length()).map { optString(it) }
