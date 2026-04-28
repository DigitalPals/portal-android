package org.connectbot.portal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class PortalViewModel(application: android.app.Application) : AndroidViewModel(application) {
    private val store = PortalStore(application)
    private val client = HubClient(store)
    private val repository = PortalHubRepository(store, client)
    private var pendingPkce: HubClient.Pkce? = null
    private var terminal: HubTerminal? = null
    private var terminalDetachRequested = false

    private val _state = MutableStateFlow(
        PortalUiState(
            hubUrl = store.hubUrl.ifBlank { "https://portal-hub.example.ts.net" },
            vaultSecretStored = store.loadVaultSecret() != null,
            vaultEnrollmentId = store.loadVaultEnrollmentId(),
            terminalFontFamily = store.terminalFontFamily,
            terminalFontSize = store.terminalFontSize,
        ),
    )
    val state: StateFlow<PortalUiState> = _state

    private val _authRequests = MutableSharedFlow<String>()
    val authRequests: SharedFlow<String> = _authRequests

    init {
        viewModelScope.launch {
            loadCachedSync()
            if (store.hubUrl.isNotBlank() && store.loadTokens() != null) {
                refreshAll()
            }
        }
    }

    fun updateHubUrl(value: String) {
        _state.update { it.copy(hubUrl = value, error = null) }
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
                val request = repository.startSignIn(state.value.hubUrl)
                pendingPkce = request.pkce
                _state.update { it.copy(hubInfo = request.hubInfo, error = null) }
                _authRequests.emit(request.authorizeUrl)
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
                refreshAll()
            }
        }
    }

    fun refreshAll() {
        viewModelScope.launch {
            runBusy {
                val snapshot = repository.refreshAll()
                _state.update {
                    it.copy(
                        signedInUser = snapshot.username,
                        sync = snapshot.sync,
                        hosts = snapshot.sync.hosts,
                        sessions = snapshot.sessions,
                        selected = if (it.selected == PortalTab.Setup) PortalTab.Hosts else it.selected,
                        vaultSecretStored = snapshot.vaultSecretStored,
                        vaultEnrollmentId = snapshot.vaultEnrollmentId,
                        error = null,
                    )
                }
            }
        }
    }

    fun select(tab: PortalTab) {
        _state.update { it.copy(selected = tab) }
    }

    fun selectSettingsSection(section: SettingsSection) {
        _state.update { it.copy(settingsSection = section) }
    }

    fun showSettingsHome() {
        _state.update { it.copy(settingsSection = SettingsSection.Home) }
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
        terminal?.close()
        terminal = null
        repository.clearTokens()
        repository.clearVaultSecret()
        _state.update {
            PortalUiState(
                hubUrl = store.hubUrl.ifBlank { it.hubUrl },
                vaultSecretStored = repository.loadVaultSecret() != null,
                vaultEnrollmentId = repository.loadVaultEnrollmentId(),
                terminalFontFamily = store.terminalFontFamily,
                terminalFontSize = store.terminalFontSize,
            )
        }
    }

    fun connect(host: PortalHost) {
        if (!host.connectable) {
            _state.update { it.copy(error = "Only SSH hosts with Portal Hub enabled can be opened on Android") }
            return
        }
        val target = HubClient.terminalTarget(host)
        val vaultKeyId = host.vaultKeyId
        if (vaultKeyId.isNullOrBlank()) {
            openTerminal(target, "${host.targetUser}@${host.hostname}")
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
        try {
            openTerminal(
                target.copy(privateKey = PortalVaultCrypto.decryptPrivateKey(key, secret)),
                "${host.targetUser}@${host.hostname}",
            )
        } catch (error: Throwable) {
            _state.update { it.copy(error = error.message ?: error.toString()) }
        }
    }

    fun editHost(id: String) {
        val host = state.value.hosts.firstOrNull { it.id == id } ?: return
        _state.update {
            it.copy(
                editHostId = id,
                editHostName = host.name,
                editHostHostname = host.hostname,
                editHostPort = host.port.toString(),
                editHostUsername = host.username,
                editHostPortalHubEnabled = host.portalHubEnabled,
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

    fun updateEditHostPortalHubEnabled(value: Boolean) {
        _state.update { it.copy(editHostPortalHubEnabled = value, error = null) }
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
                    portalHubEnabled = state.value.editHostPortalHubEnabled,
                    vaultKeyId = state.value.editHostVaultKeyId,
                )
                updateHosts(payload)
                _state.update { it.clearHostEditor() }
            }
        }
    }

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
            val secret = repository.loadOrCreateVaultSecret(vault)
            _state.update {
                it.copy(
                    vaultSecretStored = true,
                    vaultSecretInput = "",
                    error = null,
                    vaultActionMessage = "Local vault secret is ready (${secret.length} characters)",
                )
            }
        } catch (error: Throwable) {
            _state.update { it.copy(error = error.message ?: error.toString()) }
        }
    }

    fun requestVaultUnlock() {
        viewModelScope.launch {
            runBusy {
                val publicKey = repository.vaultEnrollmentPublicKeyBase64()
                val deviceName = listOf(Build.MANUFACTURER, Build.MODEL)
                    .filter { it.isNotBlank() }
                    .joinToString(" ")
                    .ifBlank { "Android device" }
                val enrollment = repository.createVaultEnrollment(deviceName, publicKey)
                repository.saveVaultEnrollmentId(enrollment.id)
                _state.update {
                    it.copy(
                        vaultEnrollmentId = enrollment.id,
                        vaultEnrollmentStatus = enrollment.status,
                        vaultActionMessage = "Vault access requested. Approve ${enrollment.deviceName} in Portal desktop.",
                        error = null,
                    )
                }
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
                handleVaultEnrollment(enrollment)
            }
        }
    }

    fun resetVaultUnlockRequest() {
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
        val clipboard = getApplication<android.app.Application>()
            .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Portal vault public key", publicKey))
        _state.update { it.copy(vaultActionMessage = "Copied public key for ${key.name}", error = null) }
    }

    private fun handleVaultEnrollment(enrollment: VaultEnrollment) {
        if (enrollment.status != "approved") {
            _state.update {
                it.copy(
                    vaultEnrollmentId = enrollment.id,
                    vaultEnrollmentStatus = enrollment.status,
                    vaultActionMessage = "Vault access request is ${enrollment.status}",
                    error = null,
                )
            }
            return
        }
        val encrypted = enrollment.encryptedSecretBase64
            ?: throw IllegalStateException("Approved vault request did not include an encrypted unlock key")
        val secret = try {
            repository.decryptVaultEnrollmentSecret(encrypted)
        } catch (error: Throwable) {
            repository.clearVaultEnrollmentId()
            repository.clearVaultEnrollmentKey()
            _state.update {
                it.copy(
                    vaultEnrollmentId = null,
                    vaultEnrollmentStatus = null,
                    vaultActionMessage = null,
                )
            }
            throw IllegalStateException(
                "Vault unlock failed. Request vault access again and approve the new request in Portal desktop.",
                error,
            )
        }
        validateVaultSecret(secret, state.value.sync?.vault ?: HubVaultConfig())
        repository.saveVaultSecret(secret)
        repository.clearVaultEnrollmentId()
        _state.update {
            it.copy(
                vaultSecretStored = true,
                vaultSecretInput = "",
                vaultEnrollmentId = null,
                vaultEnrollmentStatus = enrollment.status,
                vaultActionMessage = "Vault unlock key stored on this device",
                error = null,
            )
        }
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
            val resolver = getApplication<android.app.Application>().contentResolver
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

    fun resume(session: HubSession) {
        openTerminal(HubClient.terminalTarget(session), "${session.targetUser}@${session.targetHost}")
    }

    fun killSession(session: HubSession) {
        viewModelScope.launch {
            runBusy {
                repository.killSession(session.sessionId)
                val sessions = repository.listSessions()
                _state.update { it.copy(sessions = sessions, error = null) }
            }
        }
    }

    fun detachTerminal() {
        terminalDetachRequested = true
        val currentTerminal = terminal
        terminal = null
        currentTerminal?.close()
        _state.update {
            it.copy(
                selected = PortalTab.Sessions,
                terminalConnected = false,
                terminalStatus = "Detached",
                terminalSession = null,
            )
        }
        refreshSessions()
    }

    private fun openTerminal(target: TerminalTarget, title: String) {
        terminal?.close()
        val terminalSession = PortalTerminalSession(
            onInput = { data -> terminal?.send(data) },
            onResize = { columns, rows -> terminal?.resize(columns, rows) },
        )
        _state.update {
            it.copy(
                selected = PortalTab.Terminal,
                terminalTitle = title,
                terminalSession = terminalSession,
                terminalStatus = "Connecting...",
                terminalConnected = false,
                error = null,
            )
        }
        terminal = client.openTerminal(
            target,
            object : TerminalListener {
                override fun onStarted() {
                    _state.update { it.copy(terminalConnected = true, terminalStatus = "Connected") }
                }

                override fun onOutput(text: String) {
                    terminalSession.writeOutput(text.toByteArray(Charsets.UTF_8))
                }

                override fun onOutput(bytes: ByteArray) {
                    terminalSession.writeOutput(bytes)
                }

                override fun onError(message: String) {
                    terminalSession.writeOutput("\r\nPortal Hub terminal error: $message\r\n".toByteArray(Charsets.UTF_8))
                    _state.update { it.copy(error = message, terminalStatus = "Error") }
                }

                override fun onClosed() {
                    terminal = null
                    val status = if (terminalDetachRequested) "Detached" else "Disconnected"
                    terminalDetachRequested = false
                    _state.update {
                        it.copy(
                            selected = PortalTab.Sessions,
                            terminalConnected = false,
                            terminalStatus = status,
                            terminalSession = null,
                        )
                    }
                    refreshSessions()
                }
            },
        )
    }

    private fun refreshSessions() {
        viewModelScope.launch {
            try {
                val sessions = repository.listSessions()
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

    private suspend fun updateVault(vault: HubVaultConfig, message: String) {
        val sync = repository.putVault(vault)
        _state.update {
            it.copy(
                sync = sync,
                hosts = sync.hosts,
                selected = PortalTab.Settings,
                vaultActionMessage = message,
                error = null,
            )
        }
    }

    private suspend fun updateHosts(payload: org.json.JSONObject) {
        val sync = repository.putHosts(payload)
        _state.update {
            it.copy(
                sync = sync,
                hosts = sync.hosts,
                selected = PortalTab.Hosts,
                error = null,
            )
        }
    }

    private fun validateVaultSecret(secret: String, vault: HubVaultConfig) {
        require(secret.isNotBlank()) { "Vault secret is required" }
        vault.keys.firstOrNull()?.let {
            PortalVaultCrypto.decryptPrivateKey(it, secret)
            return
        }
        vault.secrets.firstOrNull()?.let {
            PortalVaultCrypto.decryptSecret(it, secret)
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
}

data class PortalUiState(
    val hubUrl: String,
    val loading: Boolean = false,
    val error: String? = null,
    val hubInfo: HubInfo? = null,
    val signedInUser: String? = null,
    val sync: HubSyncState? = null,
    val hosts: List<PortalHost> = emptyList(),
    val sessions: List<HubSession> = emptyList(),
    val selected: PortalTab = PortalTab.Setup,
    val terminalTitle: String = "Terminal",
    val terminalSession: PortalTerminalSession? = null,
    val terminalStatus: String = "Detached",
    val terminalConnected: Boolean = false,
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
    val editHostPortalHubEnabled: Boolean = false,
    val editHostVaultKeyId: String? = null,
    val settingsSection: SettingsSection = SettingsSection.Home,
)

enum class PortalTab {
    Setup,
    Hosts,
    Sessions,
    Terminal,
    Settings,
}

enum class SettingsSection {
    Home,
    Terminal,
    Sync,
    Vault,
    Account,
}


private fun String.ensureTrailingNewline(): String = if (endsWith("\n")) this else "$this\n"

private fun PortalUiState.clearHostEditor(): PortalUiState =
    copy(
        editHostId = null,
        editHostName = "",
        editHostHostname = "",
        editHostPort = "",
        editHostUsername = "",
        editHostPortalHubEnabled = false,
        editHostVaultKeyId = null,
    )
