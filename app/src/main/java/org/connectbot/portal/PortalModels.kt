package org.connectbot.portal

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class HubInfo(
    val apiVersion: Int,
    val version: String,
    val publicUrl: String,
    val webProxy: Boolean,
    val sessionTitles: Boolean,
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
    val vaultKeyId: String?,
    val groupId: String?,
    val raw: JSONObject,
) {
    // Portal Hub is the app's only transport, so every SSH host is connectable.
    val connectable: Boolean
        get() = protocol == "ssh"

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
    val displayName: String?,
    val targetHost: String,
    val targetPort: Int,
    val targetUser: String,
    val createdAt: String,
    val updatedAt: String,
    val previewLines: List<String> = emptyList(),
) {
    companion object {
        fun fromJson(json: JSONObject) = HubSession(
            sessionId = json.optString("session_id"),
            displayName = json.optString("display_name").trim().ifEmpty { null },
            targetHost = json.optString("target_host"),
            targetPort = json.optInt("target_port", 22),
            targetUser = json.optString("target_user"),
            createdAt = json.optString("created_at"),
            updatedAt = json.optString("updated_at"),
            previewLines = previewLines(json.optString("preview_base64")),
        )

        // The Hub preview is raw terminal log output; strip ANSI/OSC control
        // sequences so the session card can show plain trailing lines.
        fun previewLines(previewBase64: String?, maxLines: Int = 3): List<String> {
            if (previewBase64.isNullOrBlank()) return emptyList()
            val raw = runCatching {
                String(java.util.Base64.getMimeDecoder().decode(previewBase64), Charsets.UTF_8)
            }.getOrNull() ?: return emptyList()
            return raw
                .replace(Regex("\u001B\\][^\u0007\u001B]*(\u0007|\u001B\\\\)"), "")
                .replace(Regex("\u001B[\\[\\]()#;?]*[0-9;]*[A-Za-z@^_`~]"), "")
                .replace(Regex("[\u0000-\u0008\u000B-\u001F\u007F]"), "")
                .split("\n")
                .map { it.trimEnd('\r', ' ') }
                .filter { it.isNotBlank() }
                .takeLast(maxLines)
        }
    }
}

data class PortalHostGroup(
    val id: String?,
    val name: String,
    val hosts: List<PortalHost>,
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
                        vaultKeyId = auth?.optString("vault_key_id")?.ifEmpty { null },
                        groupId = host.optString("group_id").ifEmpty { null },
                        raw = host,
                    )
                }
        }

    // Groups come from the synced Portal desktop profile; hosts that reference
    // no group (or an unknown one) fall into a trailing default section.
    val hostGroups: List<PortalHostGroup>
        get() {
            val allHosts = hosts
            val payload = services["hosts"]?.payload
            val groupArray = payload?.optJSONArray("groups")
            val declared = if (groupArray == null) {
                emptyList()
            } else {
                (0 until groupArray.length())
                    .mapNotNull { groupArray.optJSONObject(it) }
                    .mapNotNull { group ->
                        val id = group.optString("id").ifEmpty { null } ?: return@mapNotNull null
                        val name = group.optString("name").ifEmpty { id }
                        PortalHostGroup(id = id, name = name, hosts = allHosts.filter { it.groupId == id })
                    }
                    .filter { it.hosts.isNotEmpty() }
            }
            val groupedIds = declared.flatMap { group -> group.hosts.map { it.id } }.toSet()
            val ungrouped = allHosts.filter { it.id !in groupedIds }
            return if (ungrouped.isEmpty()) {
                declared
            } else {
                declared + PortalHostGroup(id = null, name = if (declared.isEmpty()) "Hosts" else "Other", hosts = ungrouped)
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

    fun addHost(
        name: String,
        hostname: String,
        port: Int,
        username: String,
        vaultKeyId: String?,
    ): JSONObject {
        val currentPayload = services["hosts"]?.payload ?: JSONObject()
        val updatedPayload = JSONObject(currentPayload.toString())
        val updatedHosts = updatedPayload.optJSONArray("hosts") ?: JSONArray()
        val host = JSONObject()
            .put("id", UUID.randomUUID().toString())
            .put("name", name)
            .put("hostname", hostname)
            .put("port", port)
            .put("username", username)
            .put("protocol", "ssh")
            // Portal Hub is mandatory on Android; hosts are always hub-enabled.
            .put("portal_hub_enabled", true)
        if (!vaultKeyId.isNullOrBlank()) {
            host.put(
                "auth",
                JSONObject()
                    .put("type", "public_key")
                    .put("vault_key_id", vaultKeyId),
            )
        }
        updatedHosts.put(host)
        if (!updatedPayload.has("groups")) {
            updatedPayload.put("groups", JSONArray())
        }
        return updatedPayload.put("hosts", updatedHosts)
    }

    fun updateHost(
        id: String,
        name: String,
        hostname: String,
        port: Int,
        username: String,
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
                    .put("portal_hub_enabled", true)
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

fun JSONArray?.toStringList(): List<String> = if (this == null) emptyList() else (0 until length()).map { optString(it) }
