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

interface PortalHubRepository {
    suspend fun checkHub(rawHubUrl: String): HubInfo

    suspend fun startSignIn(rawHubUrl: String): PortalSignInRequest

    suspend fun completeSignIn(code: String, verifier: String)

    suspend fun refreshAll(): PortalRefreshSnapshot

    fun loadCachedSync(): HubSyncState?

    fun loadVaultEnrollmentId(): String?

    fun loadVaultSecret(): String?

    fun saveVaultSecret(secret: String)

    fun clearVaultSecret()

    fun clearTokens()

    fun clearVaultEnrollmentId()

    fun loadVaultDeviceEnrollmentId(): String?

    fun saveVaultDeviceEnrollmentId(id: String)

    fun clearVaultDeviceEnrollmentId()

    fun clearVaultEnrollmentKey()

    fun loadOrCreateVaultSecret(vault: HubVaultConfig): String

    fun vaultEnrollmentPublicKeyBase64(): String

    fun saveVaultEnrollmentId(id: String)

    fun decryptVaultEnrollmentSecret(encryptedSecretBase64: String): String

    suspend fun putVault(vault: HubVaultConfig): HubSyncState

    suspend fun putHosts(payload: JSONObject): HubSyncState

    suspend fun createVaultEnrollment(
        deviceName: String,
        publicKeyDerBase64: String,
        pairingId: String?,
    ): VaultEnrollment

    suspend fun loadVaultEnrollment(id: String): VaultEnrollment

    suspend fun streamVaultEnrollmentEvents(
        id: String,
        onEnrollment: (VaultEnrollment) -> Unit,
    )

    suspend fun listSessions(): List<HubSession>

    suspend fun killSession(sessionId: String)

    fun openTerminal(target: TerminalTarget, listener: TerminalListener): PortalTerminalHandle
}

class DefaultPortalHubRepository(
    private val store: PortalStore,
    private val client: HubClient,
) : PortalHubRepository {
    override suspend fun checkHub(rawHubUrl: String): HubInfo {
        val hubUrl = PortalHubUrlNormalizer.normalize(rawHubUrl)
        val info = client.fetchInfo(hubUrl)
        require(info.apiVersion >= 2 && info.webProxy && info.syncV2 && info.keyVault && info.vaultEnrollment) {
            "Portal Hub ${info.version} does not advertise the required Android capabilities"
        }
        client.requireAndroidOAuthSupport(hubUrl, client.newPkce())
        store.hubUrl = hubUrl
        return info
    }

    override suspend fun startSignIn(rawHubUrl: String): PortalSignInRequest {
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

    override suspend fun completeSignIn(code: String, verifier: String) {
        client.exchangeCode(store.hubUrl, code, verifier)
    }

    override suspend fun refreshAll(): PortalRefreshSnapshot {
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

    override fun loadCachedSync(): HubSyncState? = store.loadSyncSnapshot()?.toHubSyncState()

    override fun loadVaultEnrollmentId(): String? = store.loadVaultEnrollmentId()

    override fun loadVaultSecret(): String? = store.loadVaultSecret()

    override fun saveVaultSecret(secret: String) {
        store.saveVaultSecret(secret)
    }

    override fun clearVaultSecret() {
        store.clearVaultSecret()
    }

    override fun clearTokens() {
        store.clearTokens()
    }

    override fun clearVaultEnrollmentId() {
        store.clearVaultEnrollmentId()
    }

    override fun loadVaultDeviceEnrollmentId(): String? = store.loadVaultDeviceEnrollmentId()

    override fun saveVaultDeviceEnrollmentId(id: String) {
        store.saveVaultDeviceEnrollmentId(id)
    }

    override fun clearVaultDeviceEnrollmentId() {
        store.clearVaultDeviceEnrollmentId()
    }

    override fun clearVaultEnrollmentKey() {
        store.clearVaultEnrollmentKey()
    }

    override fun loadOrCreateVaultSecret(vault: HubVaultConfig): String = store.loadOrCreateVaultSecret(vault)

    override fun vaultEnrollmentPublicKeyBase64(): String = store.vaultEnrollmentPublicKeyBase64()

    override fun saveVaultEnrollmentId(id: String) {
        store.saveVaultEnrollmentId(id)
    }

    override fun decryptVaultEnrollmentSecret(encryptedSecretBase64: String): String = store.decryptVaultEnrollmentSecret(encryptedSecretBase64)

    override suspend fun putVault(vault: HubVaultConfig): HubSyncState = client.putVault(vault)

    override suspend fun putHosts(payload: JSONObject): HubSyncState = client.putHosts(payload)

    override suspend fun createVaultEnrollment(
        deviceName: String,
        publicKeyDerBase64: String,
        pairingId: String?,
    ): VaultEnrollment = client.createVaultEnrollment(deviceName, publicKeyDerBase64, pairingId)

    override suspend fun loadVaultEnrollment(id: String): VaultEnrollment = client.loadVaultEnrollment(id)

    override suspend fun streamVaultEnrollmentEvents(
        id: String,
        onEnrollment: (VaultEnrollment) -> Unit,
    ) {
        client.streamVaultEnrollmentEvents(id, onEnrollment)
    }

    override suspend fun listSessions(): List<HubSession> = client.listSessions()

    override suspend fun killSession(sessionId: String) {
        client.killSession(sessionId)
    }

    override fun openTerminal(target: TerminalTarget, listener: TerminalListener): PortalTerminalHandle = client.openTerminal(target, listener)
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

fun String.toHubSyncState(): HubSyncState = JSONObject(this).toHubSyncState()

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
