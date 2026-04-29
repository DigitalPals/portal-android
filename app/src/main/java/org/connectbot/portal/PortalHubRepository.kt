package org.connectbot.portal

import org.json.JSONObject

data class PortalSignInRequest(
    val hubInfo: HubInfo,
    val pkce: HubClient.Pkce,
    val authorizeUrl: String,
)

data class PortalRefreshSnapshot(
    val username: String,
    val sync: HubSyncState,
    val sessions: List<HubSession>,
    val vaultSecretStored: Boolean,
    val vaultEnrollmentId: String?,
)

class PortalHubRepository(
    private val store: PortalStore,
    private val client: HubClient,
) {
    suspend fun checkHub(rawHubUrl: String): HubInfo {
        val hubUrl = PortalHubUrlNormalizer.normalize(rawHubUrl)
        val info = client.fetchInfo(hubUrl)
        require(info.apiVersion >= 2 && info.webProxy && info.syncV2 && info.keyVault && info.vaultEnrollment) {
            "Portal Hub ${info.version} does not advertise the required Android capabilities"
        }
        client.requireAndroidOAuthSupport(hubUrl, client.newPkce())
        store.hubUrl = hubUrl
        return info
    }

    suspend fun startSignIn(rawHubUrl: String): PortalSignInRequest {
        val hubUrl = PortalHubUrlNormalizer.normalize(rawHubUrl)
        val info = client.fetchInfo(hubUrl)
        require(info.apiVersion >= 2) { "Portal Hub API version ${info.apiVersion} is too old" }
        require(info.keyVault && info.vaultEnrollment) {
            "Portal Hub ${info.version} does not advertise Android vault access"
        }
        val pkce = client.newPkce()
        client.requireAndroidOAuthSupport(hubUrl, pkce)
        store.hubUrl = hubUrl
        return PortalSignInRequest(
            hubInfo = info,
            pkce = pkce,
            authorizeUrl = client.authorizeUrl(hubUrl, pkce),
        )
    }

    suspend fun completeSignIn(code: String, verifier: String) {
        client.exchangeCode(store.hubUrl, code, verifier)
    }

    suspend fun refreshAll(): PortalRefreshSnapshot {
        val username = client.me()
        val sync = client.syncState()
        val sessions = client.listSessions()
        return PortalRefreshSnapshot(
            username = username,
            sync = sync,
            sessions = sessions,
            vaultSecretStored = store.loadVaultSecret() != null,
            vaultEnrollmentId = store.loadVaultEnrollmentId(),
        )
    }

    fun loadCachedSync(): HubSyncState? = store.loadSyncSnapshot()?.toHubSyncState()

    fun loadVaultEnrollmentId(): String? = store.loadVaultEnrollmentId()

    fun loadVaultSecret(): String? = store.loadVaultSecret()

    fun saveVaultSecret(secret: String) {
        store.saveVaultSecret(secret)
    }

    fun clearVaultSecret() {
        store.clearVaultSecret()
    }

    fun clearTokens() {
        store.clearTokens()
    }

    fun clearVaultEnrollmentId() {
        store.clearVaultEnrollmentId()
    }

    fun loadVaultDeviceEnrollmentId(): String? = store.loadVaultDeviceEnrollmentId()

    fun saveVaultDeviceEnrollmentId(id: String) {
        store.saveVaultDeviceEnrollmentId(id)
    }

    fun clearVaultDeviceEnrollmentId() {
        store.clearVaultDeviceEnrollmentId()
    }

    fun clearVaultEnrollmentKey() {
        store.clearVaultEnrollmentKey()
    }

    fun loadOrCreateVaultSecret(vault: HubVaultConfig): String =
        store.loadOrCreateVaultSecret(vault)

    fun vaultEnrollmentPublicKeyBase64(): String = store.vaultEnrollmentPublicKeyBase64()

    fun saveVaultEnrollmentId(id: String) {
        store.saveVaultEnrollmentId(id)
    }

    fun decryptVaultEnrollmentSecret(encryptedSecretBase64: String): String =
        store.decryptVaultEnrollmentSecret(encryptedSecretBase64)

    suspend fun putVault(vault: HubVaultConfig): HubSyncState = client.putVault(vault)

    suspend fun putHosts(payload: JSONObject): HubSyncState = client.putHosts(payload)

    suspend fun createVaultEnrollment(
        deviceName: String,
        publicKeyDerBase64: String,
        pairingId: String?,
    ): VaultEnrollment =
        client.createVaultEnrollment(deviceName, publicKeyDerBase64, pairingId)

    suspend fun loadVaultEnrollment(id: String): VaultEnrollment = client.loadVaultEnrollment(id)

    suspend fun streamVaultEnrollmentEvents(
        id: String,
        onEnrollment: (VaultEnrollment) -> Unit,
    ) = client.streamVaultEnrollmentEvents(id, onEnrollment)

    suspend fun listSessions(): List<HubSession> = client.listSessions()

    suspend fun killSession(sessionId: String) {
        client.killSession(sessionId)
    }
}

object PortalHubUrlNormalizer {
    fun normalize(rawHubUrl: String): String {
        val raw = rawHubUrl.trim().trimEnd('/')
        require(raw.isNotBlank()) { "Portal Hub URL is required" }
        return when {
            raw.startsWith("http://") || raw.startsWith("https://") -> raw
            raw == "localhost" || raw.startsWith("127.") -> "http://$raw:8080"
            ":" in raw -> "https://$raw"
            else -> "https://$raw:8080"
        }
    }
}

object PortalAndroidPairing {
    private const val SCHEME = "com.digitalpals.portal.android"
    private const val PAIR_PATH = "/pair"

    data class Link(
        val hubUrl: String,
        val pairingId: String?,
    )

    fun from(uri: android.net.Uri): Link? {
        if (uri.scheme != SCHEME || uri.path != PAIR_PATH) return null
        val hubUrl = uri.getQueryParameter("hub_url")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return null
        return Link(
            hubUrl = hubUrl,
            pairingId = uri.getQueryParameter("pairing_id")?.trim()?.takeIf { it.isNotBlank() },
        )
    }

    fun hubUrlFrom(uri: android.net.Uri): String? = from(uri)?.hubUrl
}

fun String.toHubSyncState(): HubSyncState {
    return JSONObject(this).toHubSyncState()
}

fun JSONObject.toHubSyncState(): HubSyncState {
    val services = objectMap("services").mapValues { (_, service) ->
        HubServiceState(
            revision = service.optString("revision", "0"),
            payload = service.optJSONObject("payload") ?: JSONObject(),
            tombstones = service.optJSONArray("tombstones").toStringList(),
        )
    }
    return HubSyncState(services)
}
