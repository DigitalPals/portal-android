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
import org.json.JSONObject

class PortalViewModel(application: android.app.Application) : AndroidViewModel(application) {
    private val store = PortalStore(application)
    private val client = HubClient(store)
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
                val hubUrl = normalizedHubUrl()
                val info = client.fetchInfo(hubUrl)
                require(info.apiVersion >= 2 && info.webProxy && info.syncV2 && info.keyVault) {
                    "Portal Hub ${info.version} does not advertise the required Android capabilities"
                }
                client.requireAndroidOAuthSupport(hubUrl, client.newPkce())
                store.hubUrl = hubUrl
                _state.update { it.copy(hubInfo = info, error = null) }
            }
        }
    }

    fun startSignIn() {
        viewModelScope.launch {
            runBusy {
                val hubUrl = normalizedHubUrl()
                val info = client.fetchInfo(hubUrl)
                require(info.apiVersion >= 2) { "Portal Hub API version ${info.apiVersion} is too old" }
                val pkce = client.newPkce()
                client.requireAndroidOAuthSupport(hubUrl, pkce)
                store.hubUrl = hubUrl
                pendingPkce = pkce
                _state.update { it.copy(hubInfo = info, error = null) }
                _authRequests.emit(client.authorizeUrl(hubUrl, pkce))
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
                client.exchangeCode(store.hubUrl, code, pkce.verifier)
                pendingPkce = null
                refreshAll()
            }
        }
    }

    fun refreshAll() {
        viewModelScope.launch {
            runBusy {
                val username = client.me()
                val sync = client.syncState()
                val sessions = client.listSessions()
                _state.update {
                    it.copy(
                        signedInUser = username,
                        sync = sync,
                        hosts = sync.hosts,
                        sessions = sessions,
                        selected = if (it.selected == PortalTab.Setup) PortalTab.Hosts else it.selected,
                        vaultSecretStored = store.loadVaultSecret() != null,
                        vaultEnrollmentId = store.loadVaultEnrollmentId(),
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
        store.clearTokens()
        store.clearVaultSecret()
        _state.update {
            PortalUiState(
                hubUrl = store.hubUrl.ifBlank { it.hubUrl },
                vaultSecretStored = store.loadVaultSecret() != null,
                vaultEnrollmentId = store.loadVaultEnrollmentId(),
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
        val secret = store.loadVaultSecret()
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

    fun updateVaultSecretInput(value: String) {
        _state.update { it.copy(vaultSecretInput = value, error = null) }
    }

    fun saveVaultSecret() {
        try {
            val secret = state.value.vaultSecretInput.trim()
            validateVaultSecret(secret, state.value.sync?.vault ?: HubVaultConfig())
            store.saveVaultSecret(secret)
            _state.update { it.copy(vaultSecretStored = true, vaultSecretInput = "", error = null) }
        } catch (error: Throwable) {
            _state.update { it.copy(error = error.message ?: error.toString()) }
        }
    }

    fun createVaultSecret() {
        try {
            val vault = state.value.sync?.vault ?: HubVaultConfig()
            val secret = store.loadOrCreateVaultSecret(vault)
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
                val publicKey = store.vaultEnrollmentPublicKeyBase64()
                val deviceName = listOf(Build.MANUFACTURER, Build.MODEL)
                    .filter { it.isNotBlank() }
                    .joinToString(" ")
                    .ifBlank { "Android device" }
                val enrollment = client.createVaultEnrollment(deviceName, publicKey)
                store.saveVaultEnrollmentId(enrollment.id)
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
        val id = store.loadVaultEnrollmentId()
        if (id.isNullOrBlank()) {
            _state.update { it.copy(error = "No vault access request is pending") }
            return
        }
        viewModelScope.launch {
            runBusy {
                val enrollment = client.loadVaultEnrollment(id)
                handleVaultEnrollment(enrollment)
            }
        }
    }

    fun resetVaultUnlockRequest() {
        store.clearVaultEnrollmentId()
        store.clearVaultEnrollmentKey()
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
            store.decryptVaultEnrollmentSecret(encrypted)
        } catch (error: Throwable) {
            store.clearVaultEnrollmentId()
            store.clearVaultEnrollmentKey()
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
        store.saveVaultSecret(secret)
        store.clearVaultEnrollmentId()
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
                val secret = store.loadOrCreateVaultSecret(vault)
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
                val sessions = client.listSessions()
                _state.update { it.copy(sessions = sessions, error = null) }
            } catch (error: Throwable) {
                _state.update { it.copy(error = error.message ?: error.toString()) }
            }
        }
    }

    private fun loadCachedSync() {
        val raw = store.loadSyncSnapshot() ?: return
        val json = JSONObject(raw)
        val services = json.objectMap("services").mapValues { (_, service) ->
            HubServiceState(
                revision = service.optString("revision", "0"),
                payload = service.optJSONObject("payload") ?: JSONObject(),
                tombstones = service.opt("tombstones") ?: emptyList<String>(),
            )
        }
        val sync = HubSyncState(services)
        _state.update {
            it.copy(
                sync = sync,
                hosts = sync.hosts,
                vaultSecretStored = store.loadVaultSecret() != null,
                vaultEnrollmentId = store.loadVaultEnrollmentId(),
            )
        }
    }

    private suspend fun updateVault(vault: HubVaultConfig, message: String) {
        val sync = client.putVault(vault)
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

    private fun normalizedHubUrl(): String {
        val raw = state.value.hubUrl.trim().trimEnd('/')
        require(raw.isNotBlank()) { "Portal Hub URL is required" }
        return when {
            raw.startsWith("http://") || raw.startsWith("https://") -> raw
            raw == "localhost" || raw.startsWith("127.") -> "http://$raw:8080"
            ":" in raw -> "https://$raw"
            else -> "https://$raw:8080"
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
