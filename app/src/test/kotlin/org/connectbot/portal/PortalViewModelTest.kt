package org.connectbot.portal

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.assertj.core.api.Assertions.assertThat
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class PortalViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var repository: FakePortalHubRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        repository = FakePortalHubRepository()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(): PortalViewModel {
        val application = ApplicationProvider.getApplicationContext<Application>()
        return PortalViewModel(application, PortalStore(application), repository)
    }

    private fun pairingUri(pairingId: String? = "pair-1"): Uri {
        val suffix = pairingId?.let { "&pairing_id=$it" } ?: ""
        return Uri.parse("com.digitalpals.portal.android:/pair?hub_url=https://hub.example$suffix")
    }

    private fun redirectUri(state: String = "oauth-state", code: String = "auth-code"): Uri = Uri.parse("com.digitalpals.portal.android:/oauth2redirect?code=$code&state=$state")

    // --- pairing and sign-in ---

    @Test
    fun `startPairing with valid link switches to setup and requests authorization`() = runTest(dispatcher) {
        val vm = viewModel()
        val authUrls = mutableListOf<String>()
        // Unconfined so the collector subscribes eagerly, before the pairing
        // flow emits the authorize URL.
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.authRequests.collect { authUrls += it }
        }

        vm.startPairing(pairingUri())
        advanceUntilIdle()

        assertThat(vm.state.value.hubUrl).isEqualTo("https://hub.example")
        assertThat(vm.state.value.selected).isEqualTo(PortalTab.Setup)
        assertThat(vm.state.value.error).isNull()
        assertThat(repository.signInHubUrls).containsExactly("https://hub.example")
        assertThat(authUrls).containsExactly("https://hub.example/authorize")
    }

    @Test
    fun `startPairing with invalid link reports error`() = runTest(dispatcher) {
        val vm = viewModel()

        vm.startPairing(Uri.parse("com.digitalpals.portal.android:/pair?pairing_id=only"))
        runCurrent()

        assertThat(vm.state.value.error).isEqualTo("Portal Android pairing link was invalid")
        assertThat(repository.signInHubUrls).isEmpty()
    }

    @Test
    fun `completeSignIn rejects mismatched oauth state`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.startPairing(pairingUri())
        advanceUntilIdle()

        vm.completeSignIn(redirectUri(state = "wrong-state"))
        advanceUntilIdle()

        assertThat(vm.state.value.error).isEqualTo("Portal Hub sign-in redirect was invalid")
        assertThat(repository.exchangedCodes).isEmpty()
    }

    @Test
    fun `completeSignIn refreshes hub state and leaves setup tab`() = runTest(dispatcher) {
        repository.syncJson = SYNC_WITHOUT_VAULT
        val vm = viewModel()
        vm.startPairing(pairingUri(pairingId = null))
        advanceUntilIdle()

        vm.completeSignIn(redirectUri())
        advanceUntilIdle()

        assertThat(repository.exchangedCodes).containsExactly("auth-code")
        assertThat(vm.state.value.signedInUser).isEqualTo("john")
        assertThat(vm.state.value.hosts).hasSize(1)
        assertThat(vm.state.value.sessions).hasSize(1)
        assertThat(vm.state.value.selected).isEqualTo(PortalTab.Hosts)
        assertThat(vm.state.value.error).isNull()
    }

    @Test
    fun `pairing id from link is forwarded to the vault enrollment request`() = runTest(dispatcher) {
        repository.syncJson = SYNC_WITH_VAULT
        val vm = viewModel()
        vm.startPairing(pairingUri(pairingId = "pair-42"))
        advanceUntilIdle()

        vm.completeSignIn(redirectUri())
        // runCurrent, not advanceUntilIdle: the auto-created enrollment starts the
        // poll loop, which re-schedules delays until the request resolves.
        runCurrent()

        assertThat(repository.createdEnrollments).hasSize(1)
        assertThat(repository.createdEnrollments.single().pairingId).isEqualTo("pair-42")
        assertThat(repository.enrollmentId).isEqualTo("enrollment-1")
        assertThat(vm.state.value.vaultEnrollmentId).isEqualTo("enrollment-1")
        assertThat(vm.state.value.vaultEnrollmentStatus).isEqualTo("pending")

        vm.resetVaultUnlockRequest()
    }

    @Test
    fun `vault access is not requested when the hub vault is empty`() = runTest(dispatcher) {
        repository.syncJson = SYNC_WITHOUT_VAULT
        val vm = viewModel()
        vm.startPairing(pairingUri())
        advanceUntilIdle()

        vm.completeSignIn(redirectUri())
        advanceUntilIdle()

        assertThat(repository.createdEnrollments).isEmpty()
        assertThat(vm.state.value.vaultEnrollmentId).isNull()
    }

    // --- vault enrollment lifecycle ---

    @Test
    fun `approved enrollment stores the decrypted vault secret`() = runTest(dispatcher) {
        repository.enrollmentId = "enrollment-1"
        repository.enrollmentQueue += enrollment(status = "approved", encryptedSecret = "ZW5jcnlwdGVk")
        val vm = viewModel()

        vm.checkVaultUnlock()
        advanceUntilIdle()

        assertThat(repository.vaultSecret).isEqualTo("unlocked-secret")
        assertThat(repository.deviceEnrollmentId).isEqualTo("enrollment-1")
        assertThat(repository.enrollmentId).isNull()
        assertThat(vm.state.value.vaultSecretStored).isTrue()
        assertThat(vm.state.value.vaultEnrollmentId).isNull()
        assertThat(vm.state.value.vaultActionMessage).isEqualTo("Vault unlock key stored on this device")
        assertThat(vm.state.value.error).isNull()
    }

    @Test
    fun `enrollment decrypt failure resets the request with guidance`() = runTest(dispatcher) {
        repository.enrollmentId = "enrollment-1"
        repository.enrollmentQueue += enrollment(status = "approved", encryptedSecret = "ZW5jcnlwdGVk")
        repository.decryptError = IllegalStateException("keystore mismatch")
        val vm = viewModel()

        vm.checkVaultUnlock()
        advanceUntilIdle()

        assertThat(repository.vaultSecret).isNull()
        assertThat(repository.enrollmentId).isNull()
        assertThat(repository.clearedEnrollmentKey).isTrue()
        assertThat(vm.state.value.vaultSecretStored).isFalse()
        assertThat(vm.state.value.error).contains("Request vault access again")
    }

    @Test
    fun `revoked enrollment clears the stored vault secret`() = runTest(dispatcher) {
        repository.enrollmentId = "enrollment-1"
        repository.deviceEnrollmentId = "enrollment-1"
        repository.vaultSecret = "stored-secret"
        repository.enrollmentQueue += enrollment(status = "revoked")
        val vm = viewModel()

        vm.checkVaultUnlock()
        advanceUntilIdle()

        assertThat(repository.vaultSecret).isNull()
        assertThat(repository.deviceEnrollmentId).isNull()
        assertThat(vm.state.value.vaultSecretStored).isFalse()
        assertThat(vm.state.value.vaultActionMessage).isEqualTo("Vault access was revoked in Portal desktop")
    }

    @Test
    fun `requestVaultUnlock polls until the enrollment is approved`() = runTest(dispatcher) {
        repository.enrollmentQueue += enrollment(status = "pending")
        repository.enrollmentQueue += enrollment(status = "approved", encryptedSecret = "ZW5jcnlwdGVk")
        val vm = viewModel()

        vm.requestVaultUnlock()
        runCurrent()
        assertThat(repository.createdEnrollments).hasSize(1)
        assertThat(vm.state.value.vaultEnrollmentStatus).isEqualTo("pending")

        advanceTimeBy(5_001)
        runCurrent()
        assertThat(vm.state.value.vaultSecretStored).isFalse()

        advanceTimeBy(5_001)
        runCurrent()
        assertThat(repository.loadedEnrollmentIds).hasSize(2)
        assertThat(repository.vaultSecret).isEqualTo("unlocked-secret")
        assertThat(vm.state.value.vaultSecretStored).isTrue()
        assertThat(vm.state.value.vaultActionMessage).isEqualTo("Vault unlock key stored on this device")
    }

    // --- terminal ---

    @Test
    fun `connect without vault key opens a hub terminal`() = runTest(dispatcher) {
        val vm = viewModel()

        vm.connect(host())
        runCurrent()

        assertThat(repository.openedTargets).hasSize(1)
        assertThat(repository.openedTargets.single().targetHost).isEqualTo("prod.example.com")
        assertThat(vm.state.value.selected).isEqualTo(PortalTab.Terminal)
        assertThat(vm.state.value.terminalStatus).isEqualTo("Connecting...")
        assertThat(vm.state.value.terminalSession).isNotNull()

        repository.lastTerminalListener!!.onStarted()
        runCurrent()
        assertThat(vm.state.value.terminalConnected).isTrue()
        assertThat(vm.state.value.terminalStatus).isEqualTo("Connected")
    }

    @Test
    fun `connect to vault host with locked vault reports locked error`() = runTest(dispatcher) {
        repository.syncJson = SYNC_WITH_VAULT
        val vm = viewModel()
        vm.startPairing(pairingUri(pairingId = null))
        advanceUntilIdle()
        vm.completeSignIn(redirectUri())
        // runCurrent, not advanceUntilIdle: the auto vault request starts the poll loop.
        runCurrent()
        vm.resetVaultUnlockRequest()

        vm.connect(host(vaultKeyId = "key-1"))
        runCurrent()

        assertThat(repository.openedTargets).isEmpty()
        assertThat(vm.state.value.error).contains("Vault is locked")
    }

    @Test
    fun `connect to non-connectable host is rejected`() = runTest(dispatcher) {
        val vm = viewModel()

        vm.connect(host(protocol = "telnet"))
        runCurrent()

        assertThat(repository.openedTargets).isEmpty()
        assertThat(vm.state.value.error)
            .isEqualTo("Only SSH hosts with Portal Hub enabled can be opened on Android")
    }

    @Test
    fun `detachTerminal closes the handle and returns to sessions`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.connect(host())
        runCurrent()

        vm.detachTerminal()
        repository.lastTerminalListener!!.onClosed()
        advanceUntilIdle()

        assertThat(repository.terminalHandle.closed).isTrue()
        assertThat(vm.state.value.selected).isEqualTo(PortalTab.Sessions)
        assertThat(vm.state.value.terminalSession).isNull()
        assertThat(vm.state.value.terminalStatus).isEqualTo("Detached")
    }

    // --- host editing ---

    @Test
    fun `saveHostDetails validates the port`() = runTest(dispatcher) {
        repository.syncJson = SYNC_WITHOUT_VAULT
        val vm = viewModel()
        vm.startPairing(pairingUri(pairingId = null))
        advanceUntilIdle()
        vm.completeSignIn(redirectUri())
        advanceUntilIdle()

        vm.editHost("host-1")
        vm.updateEditHostPort("70000")
        vm.saveHostDetails()
        runCurrent()

        assertThat(vm.state.value.error).isEqualTo("Port must be between 1 and 65535")
        assertThat(repository.putHostsPayloads).isEmpty()
    }

    @Test
    fun `saveHostDetails puts the updated host and closes the editor`() = runTest(dispatcher) {
        repository.syncJson = SYNC_WITHOUT_VAULT
        val vm = viewModel()
        vm.startPairing(pairingUri(pairingId = null))
        advanceUntilIdle()
        vm.completeSignIn(redirectUri())
        advanceUntilIdle()

        vm.editHost("host-1")
        vm.updateEditHostName("renamed")
        vm.saveHostDetails()
        advanceUntilIdle()

        assertThat(repository.putHostsPayloads).hasSize(1)
        val savedHost = repository.putHostsPayloads.single()
            .getJSONArray("hosts")
            .getJSONObject(0)
        assertThat(savedHost.getString("name")).isEqualTo("renamed")
        assertThat(vm.state.value.editHostId).isNull()
        assertThat(vm.state.value.selected).isEqualTo(PortalTab.Hosts)
    }

    // --- sessions and sign-out ---

    @Test
    fun `killSession removes the session and refreshes the list`() = runTest(dispatcher) {
        repository.syncJson = SYNC_WITHOUT_VAULT
        val vm = viewModel()
        vm.startPairing(pairingUri(pairingId = null))
        advanceUntilIdle()
        vm.completeSignIn(redirectUri())
        advanceUntilIdle()

        vm.killSession(vm.state.value.sessions.single())
        advanceUntilIdle()

        assertThat(repository.killedSessionIds).containsExactly("session-1")
        assertThat(vm.state.value.sessions).isEmpty()
    }

    @Test
    fun `signOut clears credentials and resets state`() = runTest(dispatcher) {
        repository.syncJson = SYNC_WITHOUT_VAULT
        repository.vaultSecret = "stored-secret"
        val vm = viewModel()
        vm.startPairing(pairingUri(pairingId = null))
        advanceUntilIdle()
        vm.completeSignIn(redirectUri())
        advanceUntilIdle()

        vm.signOut()
        runCurrent()

        assertThat(repository.tokensCleared).isTrue()
        assertThat(repository.vaultSecret).isNull()
        assertThat(vm.state.value.signedInUser).isNull()
        assertThat(vm.state.value.hosts).isEmpty()
        assertThat(vm.state.value.selected).isEqualTo(PortalTab.Setup)
    }

    private fun host(
        id: String = "host-1",
        vaultKeyId: String? = null,
        protocol: String = "ssh",
    ) = PortalHost(
        id = id,
        name = "prod",
        hostname = "prod.example.com",
        port = 22,
        username = "deploy",
        protocol = protocol,
        portalHubEnabled = true,
        vaultKeyId = vaultKeyId,
        raw = JSONObject(),
    )

    private fun enrollment(
        status: String,
        encryptedSecret: String? = null,
        id: String = "enrollment-1",
    ) = VaultEnrollment(
        id = id,
        deviceName = "Pixel",
        status = status,
        encryptedSecretBase64 = encryptedSecret,
        pairingId = null,
        createdAt = "2026-07-16T10:00:00Z",
        updatedAt = "2026-07-16T10:00:00Z",
        approvedAt = null,
        revokedAt = null,
    )

    companion object {
        private val SYNC_WITHOUT_VAULT = """
            {
              "services": {
                "hosts": {
                  "revision": "rev-1",
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
        """.trimIndent()

        private val SYNC_WITH_VAULT = """
            {
              "services": {
                "hosts": {
                  "revision": "rev-1",
                  "payload": {"hosts": [], "groups": []},
                  "tombstones": []
                },
                "vault": {
                  "revision": "rev-2",
                  "payload": {
                    "keys": [
                      {
                        "id": "key-1",
                        "name": "Deploy key",
                        "created_at": "2026-07-16T10:00:00Z",
                        "updated_at": "2026-07-16T10:00:00Z",
                        "encryption": {
                          "kdf": {
                            "algorithm": "Argon2id",
                            "memory_kib": 65536,
                            "iterations": 3,
                            "parallelism": 1
                          },
                          "salt_base64": "AAAAAAAAAAAAAAAAAAAAAA==",
                          "cipher": "XChaCha20Poly1305",
                          "nonce_base64": "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
                          "ciphertext_base64": "AAAA"
                        }
                      }
                    ],
                    "secrets": []
                  },
                  "tombstones": []
                }
              }
            }
        """.trimIndent()
    }
}

private class FakeTerminalHandle : PortalTerminalHandle {
    var closed = false
    val sent = mutableListOf<ByteArray>()

    override fun send(text: String) {
        sent += text.toByteArray(Charsets.UTF_8)
    }

    override fun send(bytes: ByteArray) {
        sent += bytes
    }

    override fun resize(cols: Int, rows: Int) = Unit

    override fun close() {
        closed = true
    }
}

private class FakePortalHubRepository : PortalHubRepository {
    data class CreatedEnrollment(val deviceName: String, val publicKey: String, val pairingId: String?)

    var hubInfo = HubInfo(
        apiVersion = 2,
        version = "1.0.0",
        publicUrl = "https://hub.example",
        webProxy = true,
        sessionTitles = true,
        syncV2 = true,
        syncEvents = true,
        keyVault = true,
        vaultEnrollment = true,
    )
    var pkce = HubClient.Pkce(state = "oauth-state", verifier = "verifier", challenge = "challenge")
    var syncJson = """{"services": {}}"""
    var sessions = mutableListOf(
        HubSession(
            sessionId = "session-1",
            displayName = "Production deploy",
            targetHost = "prod.example.com",
            targetPort = 22,
            targetUser = "deploy",
            createdAt = "2026-07-16T10:00:00Z",
            updatedAt = "2026-07-16T10:00:00Z",
        ),
    )

    // fake persistent storage
    var vaultSecret: String? = null
    var enrollmentId: String? = null
    var deviceEnrollmentId: String? = null
    var cachedSync: HubSyncState? = null

    // behavior knobs
    val enrollmentQueue = ArrayDeque<VaultEnrollment>()
    var decryptError: Throwable? = null

    // call records
    val signInHubUrls = mutableListOf<String>()
    val exchangedCodes = mutableListOf<String>()
    val createdEnrollments = mutableListOf<CreatedEnrollment>()
    val loadedEnrollmentIds = mutableListOf<String>()
    val putHostsPayloads = mutableListOf<JSONObject>()
    val killedSessionIds = mutableListOf<String>()
    val openedTargets = mutableListOf<TerminalTarget>()
    var lastTerminalListener: TerminalListener? = null
    val terminalHandle = FakeTerminalHandle()
    var tokensCleared = false
    var clearedEnrollmentKey = false

    override suspend fun checkHub(rawHubUrl: String): HubInfo = hubInfo

    override suspend fun startSignIn(rawHubUrl: String): PortalSignInRequest {
        val hubUrl = PortalHubUrlNormalizer.normalize(rawHubUrl)
        signInHubUrls += hubUrl
        return PortalSignInRequest(
            hubInfo = hubInfo,
            pkce = pkce,
            authorizeUrl = "$hubUrl/authorize",
        )
    }

    override suspend fun completeSignIn(code: String, verifier: String) {
        exchangedCodes += code
    }

    override suspend fun refreshAll(): PortalRefreshSnapshot = PortalRefreshSnapshot(
        username = "john",
        sync = syncJson.toHubSyncState(),
        sessions = sessions.toList(),
        vaultSecretStored = vaultSecret != null,
        vaultEnrollmentId = enrollmentId,
    )

    override fun loadCachedSync(): HubSyncState? = cachedSync

    override fun loadVaultEnrollmentId(): String? = enrollmentId

    override fun loadVaultSecret(): String? = vaultSecret

    override fun saveVaultSecret(secret: String) {
        vaultSecret = secret
    }

    override fun clearVaultSecret() {
        vaultSecret = null
    }

    override fun clearTokens() {
        tokensCleared = true
    }

    override fun clearVaultEnrollmentId() {
        enrollmentId = null
    }

    override fun loadVaultDeviceEnrollmentId(): String? = deviceEnrollmentId

    override fun saveVaultDeviceEnrollmentId(id: String) {
        deviceEnrollmentId = id
    }

    override fun clearVaultDeviceEnrollmentId() {
        deviceEnrollmentId = null
    }

    override fun clearVaultEnrollmentKey() {
        clearedEnrollmentKey = true
    }

    override fun loadOrCreateVaultSecret(vault: HubVaultConfig): String = vaultSecret ?: "created-secret".also { vaultSecret = it }

    override fun vaultEnrollmentPublicKeyBase64(): String = "cHVibGljLWtleQ=="

    override fun saveVaultEnrollmentId(id: String) {
        enrollmentId = id
    }

    override fun decryptVaultEnrollmentSecret(encryptedSecretBase64: String): String {
        decryptError?.let { throw it }
        return "unlocked-secret"
    }

    override suspend fun putVault(vault: HubVaultConfig): HubSyncState = syncJson.toHubSyncState()

    override suspend fun putHosts(payload: JSONObject): HubSyncState {
        putHostsPayloads += payload
        return syncJson.toHubSyncState()
    }

    override suspend fun createVaultEnrollment(
        deviceName: String,
        publicKeyDerBase64: String,
        pairingId: String?,
    ): VaultEnrollment {
        createdEnrollments += CreatedEnrollment(deviceName, publicKeyDerBase64, pairingId)
        return VaultEnrollment(
            id = "enrollment-1",
            deviceName = deviceName,
            status = "pending",
            encryptedSecretBase64 = null,
            pairingId = pairingId,
            createdAt = "2026-07-16T10:00:00Z",
            updatedAt = "2026-07-16T10:00:00Z",
            approvedAt = null,
            revokedAt = null,
        )
    }

    override suspend fun loadVaultEnrollment(id: String): VaultEnrollment {
        loadedEnrollmentIds += id
        return enrollmentQueue.removeFirstOrNull()
            ?: throw IllegalStateException("No enrollment result queued for $id")
    }

    override suspend fun streamVaultEnrollmentEvents(
        id: String,
        onEnrollment: (VaultEnrollment) -> Unit,
    ) {
        // Events are exercised through polling in these tests.
    }

    override suspend fun listSessions(): List<HubSession> = sessions.toList()

    override suspend fun killSession(sessionId: String) {
        killedSessionIds += sessionId
        sessions.removeAll { it.sessionId == sessionId }
    }

    override fun openTerminal(target: TerminalTarget, listener: TerminalListener): PortalTerminalHandle {
        openedTargets += target
        lastTerminalListener = listener
        return terminalHandle
    }
}
