package org.connectbot.portal

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class PortalViewModel @Inject constructor(
    application: Application,
    private val store: PortalStore,
    private val repository: PortalHubRepository,
) : AndroidViewModel(application) {
    private var pendingPkce: HubClient.Pkce? = null
    private var pendingPairingId: String? = null
    private val terminals = LinkedHashMap<String, PortalTerminalHandle>()
    private var toastJob: Job? = null

    // Vault key decryption is Argon2id and takes seconds; it must never run on
    // the main thread. Tests override this with their test dispatcher.
    internal var cryptoDispatcher: CoroutineDispatcher = Dispatchers.Default

    private val _state = MutableStateFlow(
        PortalUiState(
            hubUrl = store.hubUrl.ifBlank { "https://portal-hub.example.ts.net" },
            vaultSecretStored = store.loadVaultSecret() != null,
            vaultEnrollmentId = store.loadVaultEnrollmentId(),
            terminalFontFamily = store.terminalFontFamily,
            terminalFontSize = store.terminalFontSize,
            enabledServices = store.enabledServices,
            enrollDeviceName = store.enrollmentDeviceName.ifBlank { VaultEnrollmentManager.defaultDeviceName() },
            lastSyncAtMillis = store.lastSyncAtMillis,
        ),
    )
    val state: StateFlow<PortalUiState> = _state

    private val _authRequests = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val authRequests: SharedFlow<String> = _authRequests

    private val enrollments = VaultEnrollmentManager(viewModelScope, repository, _state)

    init {
        viewModelScope.launch {
            loadCachedSync()
            if (store.hubUrl.isNotBlank() && store.loadTokens() != null) {
                _state.update {
                    it.copy(stage = if (store.onboarded) PortalStage.App else it.stage)
                }
                refreshAll()
                repository.loadVaultEnrollmentId()?.let { enrollments.startPolling(it) }
            }
        }
    }

    // --- onboarding navigation ---

    fun startSetup() {
        _state.update { it.copy(stage = PortalStage.HubSetup, error = null) }
    }

    fun backToWelcome() {
        _state.update { it.copy(stage = PortalStage.Welcome, error = null) }
    }

    fun updateHubUrl(value: String) {
        _state.update { it.copy(hubUrl = value, hubInfo = null, error = null) }
    }

    fun checkHub() {
        viewModelScope.launch {
            runBusy {
                val info = repository.checkHub(state.value.hubUrl)
                _state.update { it.copy(hubInfo = info, error = null) }
            }
        }
    }

    fun startSignIn() {
        viewModelScope.launch {
            runBusy {
                startSignInNow(state.value.hubUrl)
            }
        }
    }

    fun startPairing(uri: Uri) {
        val pairing = PortalAndroidPairing.from(uri)
        if (pairing == null) {
            _state.update { it.copy(error = "Portal Android pairing link was invalid") }
            return
        }
        pendingPairingId = pairing.pairingId
        _state.update {
            it.copy(
                hubUrl = pairing.hubUrl,
                stage = PortalStage.HubSetup,
                vaultActionMessage = "Pairing with Portal Hub",
                error = null,
            )
        }
        viewModelScope.launch {
            runBusy {
                startSignInNow(pairing.hubUrl)
            }
        }
    }

    fun completeSignIn(uri: Uri) {
        val code = uri.getQueryParameter("code")
        val state = uri.getQueryParameter("state")
        val pkce = pendingPkce
        if (code.isNullOrBlank() || state.isNullOrBlank() || pkce == null || state != pkce.state) {
            _state.update { it.copy(error = "Portal Hub sign-in redirect was invalid") }
            return
        }
        viewModelScope.launch {
            runBusy {
                repository.completeSignIn(code, pkce.verifier)
                pendingPkce = null
                refreshAllNow(advanceOnboarding = true)
            }
        }
    }

    // --- vault enrollment step ---

    fun updateEnrollDeviceName(value: String) {
        store.enrollmentDeviceName = value
        _state.update { it.copy(enrollDeviceName = value, error = null) }
    }

    fun requestEnrollment() {
        viewModelScope.launch {
            runBusy {
                createVaultUnlockRequest(
                    "Vault access requested. Approve this device in Portal desktop.",
                )
            }
        }
    }

    fun continueFromEnroll() {
        _state.update {
            it.copy(stage = if (store.onboarded) PortalStage.App else PortalStage.Services, error = null)
        }
    }

    fun toggleService(id: String) {
        _state.update {
            val services = if (id in it.enabledServices) it.enabledServices - id else it.enabledServices + id
            it.copy(enabledServices = services)
        }
    }

    fun finishOnboarding() {
        store.onboarded = true
        store.enabledServices = state.value.enabledServices
        val sync = state.value.sync
        _state.update {
            it.copy(stage = PortalStage.App, selected = PortalTab.Hosts, view = null, error = null)
        }
        if (sync != null) {
            showToast(
                "Synced ${sync.hosts.size} hosts, ${sync.snippets.size} snippets, ${sync.vaultKeyCount} keys from Hub",
            )
        }
    }

    // --- app navigation ---

    fun select(tab: PortalTab) {
        _state.update { it.copy(selected = tab, view = null) }
    }

    fun openNewHost() {
        _state.update {
            it.copy(
                view = PortalView.NewHost,
                newHostName = "",
                newHostAddress = "",
                newHostPort = "22",
                newHostUsername = "",
                newHostVaultKeyId = it.sync?.vault?.keys?.firstOrNull()?.id,
                error = null,
            )
        }
    }

    fun closeView() {
        _state.update { it.copy(view = null) }
    }

    fun refreshAll() {
        viewModelScope.launch {
            runBusy {
                refreshAllNow(advanceOnboarding = false)
            }
        }
    }

    fun syncNow() {
        viewModelScope.launch {
            runBusy {
                refreshAllNow(advanceOnboarding = false)
                showToast("Sync complete")
            }
        }
    }

    fun updateTerminalFontFamily(value: String) {
        store.terminalFontFamily = value
        _state.update { it.copy(terminalFontFamily = value) }
    }

    fun updateTerminalFontSize(value: Float) {
        val coerced = value.coerceIn(6f, 30f)
        store.terminalFontSize = coerced
        _state.update { it.copy(terminalFontSize = coerced) }
    }

    fun signOut() {
        terminals.values.forEach { it.close() }
        terminals.clear()
        enrollments.stop()
        repository.clearTokens()
        repository.clearVaultSecret()
        repository.clearVaultDeviceEnrollmentId()
        store.onboarded = false
        _state.update {
            PortalUiState(
                hubUrl = store.hubUrl.ifBlank { it.hubUrl },
                vaultSecretStored = repository.loadVaultSecret() != null,
                vaultEnrollmentId = repository.loadVaultEnrollmentId(),
                terminalFontFamily = store.terminalFontFamily,
                terminalFontSize = store.terminalFontSize,
                enabledServices = store.enabledServices,
                enrollDeviceName = it.enrollDeviceName,
                stage = PortalStage.Welcome,
            )
        }
    }

    // --- terminals ---

    fun connect(host: PortalHost) {
        if (!host.connectable) {
            _state.update { it.copy(error = "Only SSH hosts can be opened on Android") }
            return
        }
        val existing = state.value.terminalTabs.indexOfFirst { it.hostId == host.id }
        if (existing >= 0) {
            _state.update { it.copy(view = PortalView.Terminal, activeTerminalTab = existing, error = null) }
            return
        }
        val target = HubClient.terminalTarget(host)
        val vaultKeyId = host.vaultKeyId
        if (vaultKeyId.isNullOrBlank()) {
            openTerminal(target, host, "${host.targetUser}@${host.hostname}")
            return
        }
        val vault = state.value.sync?.vault
        val key = vault?.findKey(vaultKeyId)
        val secret = repository.loadVaultSecret()
        if (vault == null || key == null) {
            _state.update { it.copy(error = "Vault key $vaultKeyId was not found in the synced vault") }
            return
        }
        if (secret == null) {
            _state.update { it.copy(error = "Vault is locked. Store this vault's secret before opening ${host.name}.") }
            return
        }
        openTerminal(target, host, "${host.targetUser}@${host.hostname}") {
            target.copy(privateKey = PortalVaultCrypto.decryptPrivateKey(key, secret))
        }
    }

    fun resume(session: HubSession) {
        val existing = state.value.terminalTabs.indexOfFirst { it.id == session.sessionId }
        if (existing >= 0) {
            _state.update { it.copy(view = PortalView.Terminal, activeTerminalTab = existing, error = null) }
            return
        }
        openTerminal(
            HubClient.terminalTarget(session),
            host = null,
            title = session.displayName ?: "${session.targetUser}@${session.targetHost}",
        )
        showToast("Re-attached — session resumed live")
    }

    fun selectTerminalTab(index: Int) {
        _state.update {
            it.copy(activeTerminalTab = index.coerceIn(0, (it.terminalTabs.size - 1).coerceAtLeast(0)))
        }
    }

    fun closeTerminalView() {
        _state.update { it.copy(view = null) }
    }

    fun killSession(session: HubSession) {
        viewModelScope.launch {
            runBusy {
                terminals.remove(session.sessionId)?.close()
                repository.killSession(session.sessionId)
                val sessions = repository.listSessions(includePreview = true)
                _state.update { current ->
                    current.copy(
                        sessions = sessions,
                        terminalTabs = current.terminalTabs.filterNot { it.id == session.sessionId },
                        error = null,
                    )
                }
                showToast("Session ended on Hub")
            }
        }
    }

    fun respondHostKey(accepted: Boolean) {
        val prompt = state.value.hostKeyPrompt ?: return
        val handle = terminals[prompt.tabId]
        handle?.respondHostKey(accepted)
        if (accepted) {
            _state.update { it.copy(hostKeyPrompt = null) }
        } else {
            terminals.remove(prompt.tabId)?.close()
            _state.update { current ->
                val tabs = current.terminalTabs.filterNot { it.id == prompt.tabId }
                current.copy(
                    hostKeyPrompt = null,
                    terminalTabs = tabs,
                    activeTerminalTab = current.activeTerminalTab.coerceIn(0, (tabs.size - 1).coerceAtLeast(0)),
                    view = if (tabs.isEmpty()) null else current.view,
                )
            }
            showToast("Connection cancelled")
        }
    }

    private fun openTerminal(
        target: TerminalTarget,
        host: PortalHost?,
        title: String,
        prepare: (suspend () -> TerminalTarget)? = null,
    ) {
        val tabId = target.sessionId
        val terminalSession = PortalTerminalSession(
            onInput = { data -> terminals[tabId]?.send(data) },
            onResize = { columns, rows -> terminals[tabId]?.resize(columns, rows) },
        )
        val tab = PortalTerminalTab(
            id = tabId,
            hostId = host?.id,
            hostLabel = host?.name ?: target.targetHost.substringBefore('.'),
            title = title,
            session = terminalSession,
            status = if (prepare != null) "Unlocking key…" else "Connecting…",
            connected = false,
        )
        _state.update {
            it.copy(
                view = PortalView.Terminal,
                terminalTabs = it.terminalTabs + tab,
                activeTerminalTab = it.terminalTabs.size,
                error = null,
            )
        }
        if (prepare == null) {
            terminals[tabId] = repository.openTerminal(target, terminalListener(tabId, terminalSession))
            return
        }
        viewModelScope.launch {
            val resolved = try {
                withContext(cryptoDispatcher) { prepare() }
            } catch (error: Throwable) {
                _state.update { current ->
                    val tabs = current.terminalTabs.filterNot { it.id == tabId }
                    current.copy(
                        terminalTabs = tabs,
                        activeTerminalTab = current.activeTerminalTab.coerceIn(0, (tabs.size - 1).coerceAtLeast(0)),
                        view = if (tabs.isEmpty() && current.view == PortalView.Terminal) null else current.view,
                        error = error.message ?: error.toString(),
                    )
                }
                return@launch
            }
            updateTab(tabId) { it.copy(status = "Connecting…") }
            terminals[tabId] = repository.openTerminal(resolved, terminalListener(tabId, terminalSession))
        }
    }

    private fun terminalListener(tabId: String, terminalSession: PortalTerminalSession): TerminalListener = object : TerminalListener {
        override fun onStarted() {
            updateTab(tabId) { it.copy(connected = true, status = "Live · via Hub") }
        }

        override fun onOutput(text: String) {
            terminalSession.writeOutput(text.toByteArray(Charsets.UTF_8))
        }

        override fun onOutput(bytes: ByteArray) {
            terminalSession.writeOutput(bytes)
        }

        override fun onHostKeyVerification(request: HostKeyVerification) {
            _state.update {
                it.copy(
                    hostKeyPrompt = HostKeyPrompt(
                        tabId = tabId,
                        host = request.host,
                        port = request.port,
                        keyType = request.keyType,
                        fingerprint = request.fingerprint,
                        oldFingerprint = request.oldFingerprint,
                    ),
                )
            }
        }

        override fun onError(message: String) {
            terminalSession.writeOutput("\r\nPortal Hub terminal error: $message\r\n".toByteArray(Charsets.UTF_8))
            updateTab(tabId) { it.copy(status = "Error", connected = false) }
            _state.update { it.copy(error = message) }
        }

        override fun onClosed() {
            terminals.remove(tabId)
            _state.update { current ->
                val tabs = current.terminalTabs.filterNot { it.id == tabId }
                current.copy(
                    terminalTabs = tabs,
                    activeTerminalTab = current.activeTerminalTab.coerceIn(0, (tabs.size - 1).coerceAtLeast(0)),
                    view = if (tabs.isEmpty() && current.view == PortalView.Terminal) null else current.view,
                    selected = if (tabs.isEmpty() && current.view == PortalView.Terminal) PortalTab.Hosts else current.selected,
                    hostKeyPrompt = if (current.hostKeyPrompt?.tabId == tabId) null else current.hostKeyPrompt,
                )
            }
            refreshSessions()
        }
    }

    private fun updateTab(tabId: String, transform: (PortalTerminalTab) -> PortalTerminalTab) {
        _state.update { current ->
            current.copy(
                terminalTabs = current.terminalTabs.map { if (it.id == tabId) transform(it) else it },
            )
        }
    }

    // --- snippets ---

    fun runSnippet(snippet: PortalSnippet) {
        val tabs = state.value.terminalTabs
        when {
            tabs.isEmpty() -> showToast("No attached sessions — open a host first")
            tabs.size == 1 -> runSnippetOn(snippet, tabs[0].id)
            else -> _state.update { it.copy(snippetPendingRun = snippet) }
        }
    }

    fun runSnippetOn(snippet: PortalSnippet, tabId: String) {
        val index = state.value.terminalTabs.indexOfFirst { it.id == tabId }
        val handle = terminals[tabId]
        if (index < 0 || handle == null) {
            _state.update { it.copy(snippetPendingRun = null) }
            showToast("Session is no longer attached")
            return
        }
        handle.send(snippet.command + "\n")
        val host = state.value.terminalTabs[index].hostLabel
        _state.update {
            it.copy(
                snippetPendingRun = null,
                showSnippetPicker = false,
                view = PortalView.Terminal,
                activeTerminalTab = index,
            )
        }
        showToast("Snippet sent to $host")
    }

    fun openSnippetPicker() {
        _state.update { it.copy(showSnippetPicker = true) }
    }

    fun dismissSnippetSheets() {
        _state.update { it.copy(showSnippetPicker = false, snippetPendingRun = null) }
    }

    // --- new host ---

    fun updateNewHostName(value: String) = _state.update { it.copy(newHostName = value, error = null) }

    fun updateNewHostAddress(value: String) = _state.update { it.copy(newHostAddress = value, error = null) }

    fun updateNewHostPort(value: String) = _state.update { it.copy(newHostPort = value, error = null) }

    fun updateNewHostUsername(value: String) = _state.update { it.copy(newHostUsername = value, error = null) }

    fun updateNewHostVaultKeyId(value: String?) = _state.update { it.copy(newHostVaultKeyId = value, error = null) }

    fun saveNewHost() {
        val current = state.value
        val hostname = current.newHostAddress.trim()
        val name = current.newHostName.trim().ifBlank { hostname.substringBefore('.') }
        val port = current.newHostPort.trim().toIntOrNull()
        if (hostname.isBlank()) {
            _state.update { it.copy(error = "Address is required") }
            return
        }
        if (port == null || port !in 1..65535) {
            _state.update { it.copy(error = "Port must be between 1 and 65535") }
            return
        }
        viewModelScope.launch {
            runBusy {
                val sync = state.value.sync ?: throw IllegalStateException("No sync state loaded")
                val payload = sync.addHost(
                    name = name,
                    hostname = hostname,
                    port = port,
                    username = current.newHostUsername.trim(),
                    vaultKeyId = current.newHostVaultKeyId,
                )
                val updated = repository.putHosts(payload)
                _state.update {
                    it.copy(sync = updated, hosts = updated.hosts, view = null, selected = PortalTab.Hosts, error = null)
                }
                showToast("Host saved · synced to Hub")
            }
        }
    }

    // --- host editing (long-press on a host) ---

    fun editHost(id: String) {
        val host = state.value.hosts.firstOrNull { it.id == id } ?: return
        _state.update {
            it.copy(
                editHostId = id,
                editHostName = host.name,
                editHostHostname = host.hostname,
                editHostPort = host.port.toString(),
                editHostUsername = host.username,
                editHostVaultKeyId = host.vaultKeyId,
                error = null,
            )
        }
    }

    fun updateEditHostName(value: String) {
        _state.update { it.copy(editHostName = value, error = null) }
    }

    fun updateEditHostHostname(value: String) {
        _state.update { it.copy(editHostHostname = value, error = null) }
    }

    fun updateEditHostPort(value: String) {
        _state.update { it.copy(editHostPort = value, error = null) }
    }

    fun updateEditHostUsername(value: String) {
        _state.update { it.copy(editHostUsername = value, error = null) }
    }

    fun updateEditHostVaultKeyId(value: String?) {
        _state.update { it.copy(editHostVaultKeyId = value, error = null) }
    }

    fun cancelEditHost() {
        _state.update { it.clearHostEditor() }
    }

    fun saveHostDetails() {
        val current = state.value
        val id = current.editHostId ?: return
        val name = current.editHostName.trim()
        val hostname = current.editHostHostname.trim()
        val port = current.editHostPort.trim().toIntOrNull()
        val username = current.editHostUsername.trim()

        if (name.isBlank()) {
            _state.update { it.copy(error = "Host name is required") }
            return
        }
        if (hostname.isBlank()) {
            _state.update { it.copy(error = "Hostname is required") }
            return
        }
        if (port == null || port !in 1..65535) {
            _state.update { it.copy(error = "Port must be between 1 and 65535") }
            return
        }

        viewModelScope.launch {
            runBusy {
                val sync = state.value.sync ?: throw IllegalStateException("No sync state loaded")
                val payload = sync.updateHost(
                    id = id,
                    name = name,
                    hostname = hostname,
                    port = port,
                    username = username,
                    vaultKeyId = state.value.editHostVaultKeyId,
                )
                val updated = repository.putHosts(payload)
                _state.update {
                    it.copy(sync = updated, hosts = updated.hosts, error = null).clearHostEditor()
                }
            }
        }
    }

    // --- vault management ---

    fun updateVaultSecretInput(value: String) {
        _state.update { it.copy(vaultSecretInput = value, error = null) }
    }

    fun saveVaultSecret() {
        try {
            val secret = state.value.vaultSecretInput.trim()
            validateVaultSecret(secret, state.value.sync?.vault ?: HubVaultConfig())
            repository.saveVaultSecret(secret)
            _state.update { it.copy(vaultSecretStored = true, vaultSecretInput = "", error = null) }
        } catch (error: Throwable) {
            _state.update { it.copy(error = error.message ?: error.toString()) }
        }
    }

    fun createVaultSecret() {
        try {
            val vault = state.value.sync?.vault ?: HubVaultConfig()
            repository.loadOrCreateVaultSecret(vault)
            _state.update {
                it.copy(
                    vaultSecretStored = true,
                    vaultSecretInput = "",
                    error = null,
                    vaultActionMessage = "New vault initialized on this device",
                )
            }
        } catch (error: Throwable) {
            _state.update { it.copy(error = error.message ?: error.toString()) }
        }
    }

    fun requestVaultUnlock() {
        viewModelScope.launch {
            runBusy {
                createVaultUnlockRequest(
                    "Vault access requested. Approve this device in Portal desktop.",
                )
            }
        }
    }

    fun checkVaultUnlock() {
        val id = repository.loadVaultEnrollmentId()
        if (id.isNullOrBlank()) {
            _state.update { it.copy(error = "No vault access request is pending") }
            return
        }
        viewModelScope.launch {
            runBusy {
                val enrollment = repository.loadVaultEnrollment(id)
                enrollments.handleEnrollment(enrollment)
            }
        }
    }

    fun resetVaultUnlockRequest() {
        enrollments.stopPolling()
        repository.clearVaultEnrollmentId()
        repository.clearVaultEnrollmentKey()
        _state.update {
            it.copy(
                vaultEnrollmentId = null,
                vaultEnrollmentStatus = null,
                vaultActionMessage = "Vault access request reset",
                error = null,
            )
        }
    }

    fun copyVaultPublicKey(id: String) {
        val key = state.value.sync?.vault?.findKey(id)
        val publicKey = key?.publicKey
        if (publicKey.isNullOrBlank()) {
            _state.update { it.copy(error = "No public key is available for this vault key") }
            return
        }
        val clipboard = getApplication<Application>()
            .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Portal vault public key", publicKey))
        _state.update { it.copy(vaultActionMessage = "Copied public key for ${key.name}", error = null) }
    }

    fun openAddVaultKey() {
        _state.update {
            it.copy(
                showAddVaultKey = true,
                newVaultKeyName = "",
                newVaultPrivateKey = "",
                vaultActionMessage = null,
                error = null,
            )
        }
    }

    fun cancelAddVaultKey() {
        _state.update { it.copy(showAddVaultKey = false, newVaultKeyName = "", newVaultPrivateKey = "") }
    }

    fun updateNewVaultKeyName(value: String) {
        _state.update { it.copy(newVaultKeyName = value, error = null) }
    }

    fun updateNewVaultPrivateKey(value: String) {
        _state.update { it.copy(newVaultPrivateKey = value, error = null) }
    }

    fun importVaultPrivateKey(uri: Uri) {
        try {
            val resolver = getApplication<Application>().contentResolver
            val content = resolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                ?: throw IllegalArgumentException("Unable to read selected private key")
            _state.update {
                it.copy(
                    newVaultPrivateKey = content,
                    newVaultKeyName = it.newVaultKeyName.ifBlank { "Imported key" },
                    error = null,
                )
            }
        } catch (error: Throwable) {
            _state.update { it.copy(error = error.message ?: error.toString()) }
        }
    }

    fun addVaultKey() {
        val current = state.value
        val name = current.newVaultKeyName.trim()
        val privateKey = current.newVaultPrivateKey.trim()
        if (name.isBlank()) {
            _state.update { it.copy(error = "Vault key name is required") }
            return
        }
        if (privateKey.isBlank()) {
            _state.update { it.copy(error = "Private key is required") }
            return
        }
        viewModelScope.launch {
            runBusy {
                val vault = state.value.sync?.vault ?: HubVaultConfig()
                val secret = repository.loadOrCreateVaultSecret(vault)
                val key = PortalVaultCrypto.encryptPrivateKey(name, privateKey.ensureTrailingNewline(), secret)
                updateVault(vault.upsertKey(key), "Added vault key")
                _state.update {
                    it.copy(
                        vaultSecretStored = true,
                        showAddVaultKey = false,
                        newVaultKeyName = "",
                        newVaultPrivateKey = "",
                    )
                }
            }
        }
    }

    fun editVaultKey(id: String) {
        val key = state.value.sync?.vault?.findKey(id) ?: return
        _state.update { it.copy(editVaultKeyId = id, editVaultKeyName = key.name, error = null) }
    }

    fun updateEditVaultKeyName(value: String) {
        _state.update { it.copy(editVaultKeyName = value, error = null) }
    }

    fun cancelEditVaultKey() {
        _state.update { it.copy(editVaultKeyId = null, editVaultKeyName = "") }
    }

    fun saveVaultKeyName() {
        val id = state.value.editVaultKeyId ?: return
        val name = state.value.editVaultKeyName.trim()
        if (name.isBlank()) {
            _state.update { it.copy(error = "Vault key name is required") }
            return
        }
        viewModelScope.launch {
            runBusy {
                val vault = state.value.sync?.vault ?: HubVaultConfig()
                updateVault(vault.renameKey(id, name), "Renamed vault key")
                _state.update { it.copy(editVaultKeyId = null, editVaultKeyName = "") }
            }
        }
    }

    fun deleteVaultKey(id: String) {
        viewModelScope.launch {
            runBusy {
                val vault = state.value.sync?.vault ?: HubVaultConfig()
                updateVault(vault.deleteKey(id), "Deleted vault key")
            }
        }
    }

    // --- internals ---

    fun dismissToast() {
        toastJob?.cancel()
        _state.update { it.copy(toast = null) }
    }

    private fun showToast(message: String) {
        toastJob?.cancel()
        _state.update { it.copy(toast = message) }
        toastJob = viewModelScope.launch {
            delay(TOAST_MILLIS)
            _state.update { it.copy(toast = null) }
        }
    }

    private fun refreshSessions() {
        viewModelScope.launch {
            try {
                val sessions = repository.listSessions(includePreview = true)
                _state.update { it.copy(sessions = sessions, error = null) }
            } catch (error: Throwable) {
                _state.update { it.copy(error = error.message ?: error.toString()) }
            }
        }
    }

    private fun loadCachedSync() {
        val sync = repository.loadCachedSync() ?: return
        _state.update {
            it.copy(
                sync = sync,
                hosts = sync.hosts,
                vaultSecretStored = repository.loadVaultSecret() != null,
                vaultEnrollmentId = repository.loadVaultEnrollmentId(),
            )
        }
    }

    private suspend fun startSignInNow(hubUrl: String) {
        val request = repository.startSignIn(hubUrl)
        pendingPkce = request.pkce
        _state.update { it.copy(hubInfo = request.hubInfo, hubUrl = hubUrl, error = null) }
        _authRequests.emit(request.authorizeUrl)
    }

    private suspend fun refreshAllNow(advanceOnboarding: Boolean) {
        val snapshot = repository.refreshAll()
        store.lastSyncAtMillis = System.currentTimeMillis()
        _state.update {
            it.copy(
                signedInUser = snapshot.username,
                sync = snapshot.sync,
                hosts = snapshot.sync.hosts,
                sessions = snapshot.sessions,
                vaultSecretStored = snapshot.vaultSecretStored,
                vaultEnrollmentId = snapshot.vaultEnrollmentId,
                lastSyncAtMillis = store.lastSyncAtMillis,
                error = null,
            )
        }
        if (!snapshot.vaultSecretStored) {
            snapshot.vaultEnrollmentId?.let { enrollments.startPolling(it) }
        } else {
            repository.loadVaultDeviceEnrollmentId()?.let { enrollments.startEvents(it) }
            enrollments.checkStoredEnrollment()
        }
        if (advanceOnboarding) {
            advanceAfterSignIn(snapshot)
        }
    }

    private suspend fun advanceAfterSignIn(snapshot: PortalRefreshSnapshot) {
        if (snapshot.vaultSecretStored) {
            _state.update {
                it.copy(stage = if (store.onboarded) PortalStage.App else PortalStage.Services)
            }
            return
        }
        if (!snapshot.sync.vault.hasItems) {
            // Nothing to unlock: this device starts a fresh vault locally and the
            // enroll step shows its completed state.
            repository.loadOrCreateVaultSecret(snapshot.sync.vault)
            _state.update {
                it.copy(
                    stage = PortalStage.Enroll,
                    vaultSecretStored = true,
                    vaultActionMessage = "New vault initialized on this device",
                )
            }
            return
        }
        _state.update { it.copy(stage = PortalStage.Enroll) }
        // A desktop-initiated pairing link carries a pairing id; request access
        // right away so the desktop can auto-approve this device.
        if (pendingPairingId != null) {
            createVaultUnlockRequest(
                "Vault access requested automatically. Approve this device in Portal desktop.",
            )
        }
    }

    private suspend fun createVaultUnlockRequest(message: String): VaultEnrollment {
        val enrollment = enrollments.createUnlockRequest(
            message,
            pendingPairingId,
            state.value.enrollDeviceName,
        )
        pendingPairingId = null
        return enrollment
    }

    private suspend fun updateVault(vault: HubVaultConfig, message: String) {
        val sync = repository.putVault(vault)
        _state.update {
            it.copy(
                sync = sync,
                hosts = sync.hosts,
                vaultActionMessage = message,
                error = null,
            )
        }
    }

    private suspend fun runBusy(block: suspend () -> Unit) {
        _state.update { it.copy(loading = true, error = null) }
        try {
            block()
        } catch (error: Throwable) {
            _state.update { it.copy(error = error.message ?: error.toString()) }
        } finally {
            _state.update { it.copy(loading = false) }
        }
    }

    companion object {
        private const val TOAST_MILLIS = 2_600L
    }
}

data class PortalTerminalTab(
    val id: String,
    val hostId: String?,
    val hostLabel: String,
    val title: String,
    val session: PortalTerminalSession,
    val status: String,
    val connected: Boolean,
)

data class HostKeyPrompt(
    val tabId: String,
    val host: String,
    val port: Int,
    val keyType: String,
    val fingerprint: String,
    val oldFingerprint: String?,
)

data class PortalUiState(
    val hubUrl: String,
    val stage: PortalStage = PortalStage.Welcome,
    val selected: PortalTab = PortalTab.Hosts,
    val view: PortalView? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val toast: String? = null,
    val hubInfo: HubInfo? = null,
    val signedInUser: String? = null,
    val sync: HubSyncState? = null,
    val hosts: List<PortalHost> = emptyList(),
    val sessions: List<HubSession> = emptyList(),
    val terminalTabs: List<PortalTerminalTab> = emptyList(),
    val activeTerminalTab: Int = 0,
    val hostKeyPrompt: HostKeyPrompt? = null,
    val snippetPendingRun: PortalSnippet? = null,
    val showSnippetPicker: Boolean = false,
    val enabledServices: Set<String> = PortalStore.DEFAULT_SERVICES,
    val enrollDeviceName: String = "",
    val lastSyncAtMillis: Long = 0L,
    val terminalFontFamily: String = PortalStore.DEFAULT_TERMINAL_FONT_FAMILY,
    val terminalFontSize: Float = PortalStore.DEFAULT_TERMINAL_FONT_SIZE,
    val vaultSecretStored: Boolean = false,
    val vaultSecretInput: String = "",
    val vaultEnrollmentId: String? = null,
    val vaultEnrollmentStatus: String? = null,
    val vaultActionMessage: String? = null,
    val showAddVaultKey: Boolean = false,
    val newVaultKeyName: String = "",
    val newVaultPrivateKey: String = "",
    val editVaultKeyId: String? = null,
    val editVaultKeyName: String = "",
    val editHostId: String? = null,
    val editHostName: String = "",
    val editHostHostname: String = "",
    val editHostPort: String = "",
    val editHostUsername: String = "",
    val editHostVaultKeyId: String? = null,
    val newHostName: String = "",
    val newHostAddress: String = "",
    val newHostPort: String = "22",
    val newHostUsername: String = "",
    val newHostVaultKeyId: String? = null,
) {
    val activeTab: PortalTerminalTab?
        get() = terminalTabs.getOrNull(activeTerminalTab)

    fun serviceEnabled(id: String): Boolean = id in enabledServices
}

enum class PortalStage {
    Welcome,
    HubSetup,
    Enroll,
    Services,
    App,
}

enum class PortalTab {
    Hosts,
    Sessions,
    Snippets,
    Ports,
    Settings,
}

enum class PortalView {
    Terminal,
    NewHost,
}

private fun String.ensureTrailingNewline(): String = if (endsWith("\n")) this else "$this\n"

private fun PortalUiState.clearHostEditor(): PortalUiState = copy(
    editHostId = null,
    editHostName = "",
    editHostHostname = "",
    editHostPort = "",
    editHostUsername = "",
    editHostVaultKeyId = null,
)
