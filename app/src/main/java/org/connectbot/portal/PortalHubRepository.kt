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
        require(info.apiVersion >= 2 && info.webProxy && info.syncV2 && info.keyVault) {
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

    suspend fun createVaultEnrollment(
        deviceName: String,
        publicKeyDerBase64: String,
    ): VaultEnrollment =
        client.createVaultEnrollment(deviceName, publicKeyDerBase64)

    suspend fun loadVaultEnrollment(id: String): VaultEnrollment = client.loadVaultEnrollment(id)

    suspend fun listSessions(): List<HubSession> = client.listSessions()
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

fun String.toHubSyncState(): HubSyncState {
    val json = JSONObject(this)
    val services = json.objectMap("services").mapValues { (_, service) ->
        HubServiceState(
            revision = service.optString("revision", "0"),
            payload = service.optJSONObject("payload") ?: JSONObject(),
            tombstones = service.optJSONArray("tombstones").toStringList(),
        )
    }
    return HubSyncState(services)
}
