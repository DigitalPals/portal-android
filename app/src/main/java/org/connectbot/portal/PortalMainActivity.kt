package org.connectbot.portal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.connectbot.service.TerminalKeyListener
import org.connectbot.terminal.DelKeyMode
import org.connectbot.terminal.Terminal
import org.connectbot.terminal.VTermKey
import org.connectbot.ui.components.TERMINAL_KEYBOARD_HEIGHT_DP
import org.connectbot.util.TerminalFont
import org.connectbot.util.rememberTerminalTypefaceFromStoredValue
import org.json.JSONObject

class PortalMainActivity : ComponentActivity() {
    private lateinit var viewModel: PortalViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        viewModel = ViewModelProvider(this)[PortalViewModel::class.java]
        handleOAuthRedirect(intent)
        setContent {
            val uriHandler = LocalUriHandler.current
            LaunchedEffect(Unit) {
                viewModel.authRequests.collect { uriHandler.openUri(it) }
            }
            PortalTheme {
                PortalApp(viewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleOAuthRedirect(intent)
    }

    private fun handleOAuthRedirect(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme == "com.digitalpals.portal.android" && uri.path == "/oauth2redirect") {
            viewModel.completeSignIn(uri)
        }
    }
}

class PortalViewModel(application: android.app.Application) : AndroidViewModel(application) {
    private val store = PortalStore(application)
    private val client = HubClient(store)
    private var pendingPkce: HubClient.Pkce? = null
    private var terminal: HubTerminal? = null

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

    fun forgetVaultSecret() {
        store.clearVaultSecret()
        _state.update { it.copy(vaultSecretStored = false, vaultSecretInput = "", vaultActionMessage = "Local vault secret forgotten") }
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

    fun copyVaultSecret() {
        val secret = store.loadVaultSecret()
        if (secret == null) {
            _state.update { it.copy(error = "No local vault secret is stored") }
            return
        }
        val clipboard = getApplication<android.app.Application>()
            .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Portal vault secret", secret))
        _state.update { it.copy(vaultActionMessage = "Copied local vault secret", error = null) }
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
        terminal?.close()
        terminal = null
        _state.update {
            it.copy(
                selected = PortalTab.Sessions,
                terminalConnected = false,
                terminalStatus = "Detached",
                terminalSession = null,
            )
        }
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
                    _state.update {
                        it.copy(
                            selected = PortalTab.Sessions,
                            terminalConnected = false,
                            terminalStatus = "Disconnected",
                            terminalSession = null,
                        )
                    }
                }
            },
        )
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
)

enum class PortalTab {
    Setup,
    Hosts,
    Sessions,
    Terminal,
    Settings,
}

@Composable
private fun PortalTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Color(0xFF0F766E),
            secondary = Color(0xFF475569),
            tertiary = Color(0xFF9333EA),
            surface = Color(0xFFF8FAFC),
            background = Color(0xFFF1F5F9),
        ),
        content = content,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PortalApp(viewModel: PortalViewModel) {
    val state by viewModel.state.collectAsState()
    val terminalMode = state.selected == PortalTab.Terminal
    Scaffold(
        topBar = {
            if (!terminalMode) {
                TopAppBar(
                    title = { Text("Portal") },
                    actions = {
                        IconButton(onClick = viewModel::refreshAll, enabled = state.signedInUser != null && !state.loading) {
                            Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                        }
                        IconButton(onClick = viewModel::signOut, enabled = state.signedInUser != null) {
                            Icon(Icons.Filled.Logout, contentDescription = "Sign out")
                        }
                    },
                )
            }
        },
        bottomBar = {
            if (state.signedInUser != null && !terminalMode) {
                NavigationBar {
                    PortalTab.entries.filter { it != PortalTab.Setup }.forEach { tab ->
                        NavigationBarItem(
                            selected = state.selected == tab,
                            onClick = { viewModel.select(tab) },
                            icon = {
                                Icon(
                                    imageVector = when (tab) {
                                        PortalTab.Hosts -> Icons.Filled.PlayArrow
                                        PortalTab.Sessions -> Icons.Filled.List
                                        PortalTab.Terminal -> Icons.Filled.Terminal
                                        PortalTab.Settings -> Icons.Filled.Settings
                                        PortalTab.Setup -> Icons.Filled.Settings
                                    },
                                    contentDescription = tab.name,
                                )
                            },
                            label = { Text(tab.name) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        Surface(
            Modifier
                .fillMaxSize()
                .then(if (terminalMode) Modifier else Modifier.padding(padding)),
        ) {
            if (terminalMode) {
                TerminalScreen(state, viewModel)
            } else {
                Column(Modifier.fillMaxSize().padding(16.dp)) {
                    StatusLine(state)
                    when (state.selected) {
                        PortalTab.Setup -> SetupScreen(state, viewModel)
                        PortalTab.Hosts -> HostsScreen(state, viewModel)
                        PortalTab.Sessions -> SessionsScreen(state, viewModel)
                        PortalTab.Terminal -> TerminalScreen(state, viewModel)
                        PortalTab.Settings -> SettingsScreen(state, viewModel)
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusLine(state: PortalUiState) {
    val message = when {
        state.loading -> "Working..."
        state.error != null -> state.error
        state.signedInUser != null -> "Signed in as ${state.signedInUser}"
        state.hubInfo != null -> "Portal Hub ${state.hubInfo.version} is compatible"
        else -> "Configure a private Portal Hub URL"
    }
    Text(
        text = message,
        color = if (state.error == null) MaterialTheme.colorScheme.secondary else Color(0xFFB91C1C),
        modifier = Modifier.padding(bottom = 12.dp),
    )
}

@Composable
private fun SetupScreen(state: PortalUiState, viewModel: PortalViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = state.hubUrl,
            onValueChange = viewModel::updateHubUrl,
            label = { Text("Portal Hub URL") },
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                keyboardType = KeyboardType.Uri,
            ),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = viewModel::checkHub, enabled = !state.loading) {
                Text("Check")
            }
            Button(onClick = viewModel::startSignIn, enabled = !state.loading) {
                Text("Sign in")
            }
        }
        Text("Portal Android only talks to Portal Hub. Direct SSH, Telnet, local shell, and VNC transports are not registered by this app.")
    }
}

@Composable
private fun HostsScreen(state: PortalUiState, viewModel: PortalViewModel) {
    if (state.hosts.isEmpty()) {
        EmptyText("No synced hosts. Sync a Portal desktop profile through Portal Hub.")
        return
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(state.hosts, key = { it.id }) { host ->
            Card(
                colors = CardDefaults.cardColors(containerColor = Color.White),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(host.name, style = MaterialTheme.typography.titleMedium)
                    Text("${host.targetUser}@${host.hostname}:${host.port}", color = MaterialTheme.colorScheme.secondary)
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { viewModel.connect(host) }, enabled = host.connectable) {
                        Text(if (host.connectable) "Open through Hub" else "Hub disabled")
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionsScreen(state: PortalUiState, viewModel: PortalViewModel) {
    if (state.sessions.isEmpty()) {
        EmptyText("No active Portal Hub sessions.")
        return
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(state.sessions, key = { it.sessionId }) { session ->
            Card(
                colors = CardDefaults.cardColors(containerColor = Color.White),
                modifier = Modifier.fillMaxWidth().clickable { viewModel.resume(session) },
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text("${session.targetUser}@${session.targetHost}", style = MaterialTheme.typography.titleMedium)
                    Text("Updated ${session.updatedAt}", color = MaterialTheme.colorScheme.secondary)
                    if (session.preview.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(session.preview.takeLast(500), fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(state: PortalUiState, viewModel: PortalViewModel) {
    val vault = state.sync?.vault ?: HubVaultConfig()
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) viewModel.importVaultPrivateKey(uri)
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Color.White), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Terminal", style = MaterialTheme.typography.titleMedium)
                    Text("Font", style = MaterialTheme.typography.labelLarge)
                    TerminalFont.entries.forEach { option ->
                        OutlinedButton(
                            onClick = { viewModel.updateTerminalFontFamily(option.name) },
                            enabled = state.terminalFontFamily != option.name,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(if (state.terminalFontFamily == option.name) "${option.displayName} (selected)" else option.displayName)
                        }
                    }
                    Text("Font size: ${state.terminalFontSize.toInt()}sp", style = MaterialTheme.typography.labelLarge)
                    Slider(
                        value = state.terminalFontSize,
                        onValueChange = viewModel::updateTerminalFontSize,
                        valueRange = 6f..22f,
                        steps = 15,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        item {
            Card(colors = CardDefaults.cardColors(containerColor = Color.White), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Sync", style = MaterialTheme.typography.titleMedium)
                    SyncScreen(state)
                }
            }
        }

        item {
            Card(colors = CardDefaults.cardColors(containerColor = Color.White), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Vault", style = MaterialTheme.typography.titleMedium)
                    SummaryRow("Encrypted keys", vault.keys.size.toString())
                    SummaryRow("Encrypted secrets", vault.secrets.size.toString())
                    SummaryRow("Vault unlock key", if (state.vaultSecretStored) "Stored on device" else "Not stored")
                    state.vaultEnrollmentId?.let {
                        SummaryRow("Unlock request", state.vaultEnrollmentStatus ?: "Pending approval")
                    }
                    state.vaultActionMessage?.let {
                        Text(it, color = MaterialTheme.colorScheme.secondary)
                    }
                    if (!state.vaultSecretStored && !vault.hasItems) {
                        OutlinedTextField(
                            value = state.vaultSecretInput,
                            onValueChange = viewModel::updateVaultSecretInput,
                            label = { Text("Existing vault unlock key") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (state.vaultSecretStored) {
                            OutlinedButton(
                                onClick = viewModel::copyVaultSecret,
                                enabled = !state.loading,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("Copy secret")
                            }
                            OutlinedButton(
                                onClick = viewModel::forgetVaultSecret,
                                enabled = !state.loading,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("Forget secret")
                            }
                        } else if (vault.hasItems) {
                            Button(
                                onClick = viewModel::requestVaultUnlock,
                                enabled = !state.loading && state.vaultEnrollmentId == null,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Icon(Icons.Filled.Key, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("Request access")
                            }
                            OutlinedButton(
                                onClick = viewModel::checkVaultUnlock,
                                enabled = !state.loading && state.vaultEnrollmentId != null,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Icon(Icons.Filled.Refresh, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("Check approval")
                            }
                            if (state.vaultEnrollmentId != null) {
                                TextButton(onClick = viewModel::resetVaultUnlockRequest, enabled = !state.loading) {
                                    Text("Reset request")
                                }
                            }
                        } else {
                            Button(
                                onClick = viewModel::saveVaultSecret,
                                enabled = state.vaultSecretInput.isNotBlank() && !state.loading,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Icon(Icons.Filled.Save, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("Store")
                            }
                            OutlinedButton(
                                onClick = viewModel::createVaultSecret,
                                enabled = !vault.hasItems && !state.loading,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("Create")
                            }
                        }
                    }
                    Button(onClick = viewModel::openAddVaultKey, enabled = !state.loading, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Filled.Key, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Add key")
                    }
                }
            }
        }

        if (state.showAddVaultKey) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = Color.White), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Add Vault Key", style = MaterialTheme.typography.titleMedium)
                        OutlinedTextField(
                            value = state.newVaultKeyName,
                            onValueChange = viewModel::updateNewVaultKeyName,
                            label = { Text("Name") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = state.newVaultPrivateKey,
                            onValueChange = viewModel::updateNewVaultPrivateKey,
                            label = { Text("Private key") },
                            minLines = 6,
                            maxLines = 10,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { importLauncher.launch("*/*") }, enabled = !state.loading) {
                                Icon(Icons.Filled.UploadFile, contentDescription = null)
                                Text("Import")
                            }
                            Button(onClick = viewModel::addVaultKey, enabled = !state.loading) {
                                Icon(Icons.Filled.Save, contentDescription = null)
                                Text("Save")
                            }
                            TextButton(onClick = viewModel::cancelAddVaultKey) {
                                Text("Cancel")
                            }
                        }
                    }
                }
            }
        }

        if (vault.keys.isEmpty() && vault.secrets.isEmpty()) {
            item { EmptyText("No vault items. Add an SSH private key to store it as a Portal-encrypted vault blob.") }
        }

        items(vault.keys, key = { it.id }) { key ->
            VaultKeyCard(key, state, viewModel)
        }

        items(vault.secrets, key = { it.id }) { secret ->
            Card(colors = CardDefaults.cardColors(containerColor = Color.White), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(secret.name, style = MaterialTheme.typography.titleMedium)
                    Text("Secret: ${secret.kind}", color = MaterialTheme.colorScheme.secondary)
                    Text("Updated ${secret.updatedAt}", color = MaterialTheme.colorScheme.secondary)
                }
            }
        }
    }
}

@Composable
private fun SyncScreen(state: PortalUiState) {
    val sync = state.sync
    if (sync == null) {
        EmptyText("No sync state loaded.")
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SummaryRow("Hosts", sync.hosts.size.toString())
        SummaryRow("Snippets", sync.snippets.size.toString())
        SummaryRow("Vault keys", sync.vaultKeyCount.toString())
        SummaryRow("Vault secrets", sync.vaultSecretCount.toString())
        Divider()
        Text("Unknown fields from Portal desktop payloads are preserved in the cached sync snapshot.")
    }
}

@Composable
private fun VaultScreen(state: PortalUiState, viewModel: PortalViewModel) {
    val vault = state.sync?.vault ?: HubVaultConfig()
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) viewModel.importVaultPrivateKey(uri)
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SummaryRow("Encrypted keys", vault.keys.size.toString())
                SummaryRow("Encrypted secrets", vault.secrets.size.toString())
                SummaryRow("Vault unlock key", if (state.vaultSecretStored) "Stored on device" else "Not stored")
                state.vaultEnrollmentId?.let {
                    SummaryRow("Unlock request", state.vaultEnrollmentStatus ?: "Pending approval")
                }
                state.vaultActionMessage?.let {
                    Text(it, color = MaterialTheme.colorScheme.secondary)
                }
                if (!state.vaultSecretStored && !vault.hasItems) {
                    OutlinedTextField(
                        value = state.vaultSecretInput,
                        onValueChange = viewModel::updateVaultSecretInput,
                        label = { Text("Existing vault unlock key") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.vaultSecretStored) {
                        OutlinedButton(onClick = viewModel::copyVaultSecret, enabled = !state.loading) {
                            Text("Copy secret")
                        }
                        OutlinedButton(onClick = viewModel::forgetVaultSecret, enabled = !state.loading) {
                            Text("Forget secret")
                        }
                    } else if (vault.hasItems) {
                        Button(onClick = viewModel::requestVaultUnlock, enabled = !state.loading && state.vaultEnrollmentId == null) {
                            Icon(Icons.Filled.Key, contentDescription = null)
                            Text("Request access")
                        }
                        OutlinedButton(onClick = viewModel::checkVaultUnlock, enabled = !state.loading && state.vaultEnrollmentId != null) {
                            Icon(Icons.Filled.Refresh, contentDescription = null)
                            Text("Check approval")
                        }
                        if (state.vaultEnrollmentId != null) {
                            TextButton(onClick = viewModel::resetVaultUnlockRequest, enabled = !state.loading) {
                                Text("Reset request")
                            }
                        }
                    } else {
                        Button(onClick = viewModel::saveVaultSecret, enabled = state.vaultSecretInput.isNotBlank() && !state.loading) {
                            Icon(Icons.Filled.Save, contentDescription = null)
                            Text("Store")
                        }
                        OutlinedButton(onClick = viewModel::createVaultSecret, enabled = !vault.hasItems && !state.loading) {
                            Text("Create")
                        }
                    }
                    Button(onClick = viewModel::openAddVaultKey, enabled = !state.loading) {
                        Icon(Icons.Filled.Key, contentDescription = null)
                        Text("Add key")
                    }
                }
            }
        }

        if (state.showAddVaultKey) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = Color.White), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Add Vault Key", style = MaterialTheme.typography.titleMedium)
                        OutlinedTextField(
                            value = state.newVaultKeyName,
                            onValueChange = viewModel::updateNewVaultKeyName,
                            label = { Text("Name") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = state.newVaultPrivateKey,
                            onValueChange = viewModel::updateNewVaultPrivateKey,
                            label = { Text("Private key") },
                            minLines = 6,
                            maxLines = 10,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { importLauncher.launch("*/*") }, enabled = !state.loading) {
                                Icon(Icons.Filled.UploadFile, contentDescription = null)
                                Text("Import")
                            }
                            Button(onClick = viewModel::addVaultKey, enabled = !state.loading) {
                                Icon(Icons.Filled.Save, contentDescription = null)
                                Text("Save")
                            }
                            TextButton(onClick = viewModel::cancelAddVaultKey) {
                                Text("Cancel")
                            }
                        }
                    }
                }
            }
        }

        if (vault.keys.isEmpty() && vault.secrets.isEmpty()) {
            item { EmptyText("No vault items. Add an SSH private key to store it as a Portal-encrypted vault blob.") }
        }

        items(vault.keys, key = { it.id }) { key ->
            VaultKeyCard(key, state, viewModel)
        }

        items(vault.secrets, key = { it.id }) { secret ->
            Card(colors = CardDefaults.cardColors(containerColor = Color.White), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(secret.name, style = MaterialTheme.typography.titleMedium)
                    Text("Secret: ${secret.kind}", color = MaterialTheme.colorScheme.secondary)
                    Text("Updated ${secret.updatedAt}", color = MaterialTheme.colorScheme.secondary)
                }
            }
        }
    }
}

@Composable
private fun VaultKeyCard(key: VaultKey, state: PortalUiState, viewModel: PortalViewModel) {
    Card(colors = CardDefaults.cardColors(containerColor = Color.White), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (state.editVaultKeyId == key.id) {
                OutlinedTextField(
                    value = state.editVaultKeyName,
                    onValueChange = viewModel::updateEditVaultKeyName,
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = viewModel::saveVaultKeyName, enabled = !state.loading) {
                        Icon(Icons.Filled.Save, contentDescription = null)
                        Text("Save")
                    }
                    TextButton(onClick = viewModel::cancelEditVaultKey) {
                        Text("Cancel")
                    }
                }
            } else {
                Text(key.name, style = MaterialTheme.typography.titleMedium)
                Text(key.algorithm ?: "Encrypted SSH key", color = MaterialTheme.colorScheme.secondary)
                key.fingerprint?.let { Text(it, color = MaterialTheme.colorScheme.secondary) }
                key.publicKey?.let { Text(it.take(120), color = MaterialTheme.colorScheme.secondary) }
                Text("Updated ${key.updatedAt}", color = MaterialTheme.colorScheme.secondary)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { viewModel.editVaultKey(key.id) }, enabled = !state.loading) {
                        Icon(Icons.Filled.Edit, contentDescription = null)
                        Text("Rename")
                    }
                    TextButton(onClick = { viewModel.deleteVaultKey(key.id) }, enabled = !state.loading) {
                        Icon(Icons.Filled.Delete, contentDescription = null)
                        Text("Delete")
                    }
                }
            }
        }
    }
}

@Composable
private fun TerminalScreen(state: PortalUiState, viewModel: PortalViewModel) {
    val session = state.terminalSession
    val focusRequester = remember { FocusRequester() }
    val terminalTypeface = rememberTerminalTypefaceFromStoredValue(state.terminalFontFamily)

    LaunchedEffect(session) {
        if (session != null) {
            focusRequester.requestFocus()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF050607)),
    ) {
        if (session == null) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(state.terminalStatus, color = Color(0xFFE6EDF3))
            }
            return@Box
        }

        Terminal(
            terminalEmulator = session.emulator,
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = TERMINAL_KEYBOARD_HEIGHT_DP.dp),
            typeface = terminalTypeface,
            initialFontSize = state.terminalFontSize.sp,
            keyboardEnabled = true,
            showSoftKeyboard = true,
            focusRequester = focusRequester,
            modifierManager = session.keyHandler,
            onTerminalTap = {},
            delKeyMode = DelKeyMode.Delete,
        )

        Row(
            Modifier
                .fillMaxWidth()
                .background(Color(0xDD1F2937))
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(state.terminalTitle, color = Color.White, style = MaterialTheme.typography.labelLarge)
                Text(state.terminalStatus, color = Color(0xFFCBD5E1), style = MaterialTheme.typography.labelSmall)
            }
            TextButton(onClick = viewModel::detachTerminal) {
                Text("Detach", color = Color.White)
            }
        }

        PortalTerminalKeyboard(
            keyHandler = session.keyHandler,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .imePadding()
                .navigationBarsPadding(),
        )
    }
}

@Composable
private fun PortalTerminalKeyboard(
    keyHandler: TerminalKeyListener,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .height(TERMINAL_KEYBOARD_HEIGHT_DP.dp)
            .background(Color(0xEE111827))
            .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PortalTerminalKey("CTRL") {
            keyHandler.metaPress(TerminalKeyListener.CTRL_ON, true)
        }
        PortalTerminalKey("ESC") {
            keyHandler.sendEscape()
        }
        PortalTerminalKey("TAB") {
            keyHandler.sendTab()
        }
        PortalTerminalKey("↑") {
            keyHandler.sendPressedKey(VTermKey.UP)
        }
        PortalTerminalKey("↓") {
            keyHandler.sendPressedKey(VTermKey.DOWN)
        }
        PortalTerminalKey("←") {
            keyHandler.sendPressedKey(VTermKey.LEFT)
        }
        PortalTerminalKey("→") {
            keyHandler.sendPressedKey(VTermKey.RIGHT)
        }
        PortalTerminalKey("PgUp") {
            keyHandler.sendPressedKey(VTermKey.PAGEUP)
        }
        PortalTerminalKey("PgDn") {
            keyHandler.sendPressedKey(VTermKey.PAGEDOWN)
        }
        PortalTerminalKey("Home") {
            keyHandler.sendPressedKey(VTermKey.HOME)
        }
        PortalTerminalKey("End") {
            keyHandler.sendPressedKey(VTermKey.END)
        }
    }
}

@Composable
private fun PortalTerminalKey(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .height(TERMINAL_KEYBOARD_HEIGHT_DP.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Color.White, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun SummaryRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label)
        Text(value, color = MaterialTheme.colorScheme.secondary)
    }
}

@Composable
private fun EmptyText(text: String) {
    Text(text, color = MaterialTheme.colorScheme.secondary)
}

private fun String.ensureTrailingNewline(): String = if (endsWith("\n")) this else "$this\n"
