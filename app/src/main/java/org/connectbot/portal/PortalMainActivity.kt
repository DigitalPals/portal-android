package org.connectbot.portal

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.UUID

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
                        error = null,
                    )
                }
            }
        }
    }

    fun select(tab: PortalTab) {
        _state.update { it.copy(selected = tab) }
    }

    fun signOut() {
        terminal?.close()
        terminal = null
        store.clearTokens()
        _state.update {
            PortalUiState(hubUrl = store.hubUrl.ifBlank { it.hubUrl })
        }
    }

    fun connect(host: PortalHost) {
        if (!host.connectable) {
            _state.update { it.copy(error = "Only SSH hosts with Portal Hub enabled can be opened on Android") }
            return
        }
        openTerminal(HubClient.terminalTarget(host), "${host.targetUser}@${host.hostname}")
    }

    fun resume(session: HubSession) {
        openTerminal(HubClient.terminalTarget(session), "${session.targetUser}@${session.targetHost}")
    }

    fun sendTerminalInput() {
        val input = state.value.terminalInput
        if (input.isEmpty()) return
        terminal?.send(input + "\n")
        _state.update { it.copy(terminalInput = "") }
    }

    fun updateTerminalInput(value: String) {
        _state.update { it.copy(terminalInput = value) }
    }

    fun detachTerminal() {
        terminal?.close()
        terminal = null
        _state.update { it.copy(terminalConnected = false, terminalStatus = "Detached") }
    }

    private fun openTerminal(target: TerminalTarget, title: String) {
        terminal?.close()
        _state.update {
            it.copy(
                selected = PortalTab.Terminal,
                terminalTitle = title,
                terminalText = "",
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
                    _state.update { current ->
                        val next = (current.terminalText + text).takeLast(80_000)
                        current.copy(terminalText = next)
                    }
                }

                override fun onError(message: String) {
                    _state.update { it.copy(error = message, terminalStatus = "Error") }
                }

                override fun onClosed() {
                    _state.update { it.copy(terminalConnected = false, terminalStatus = "Disconnected") }
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
        _state.update { it.copy(sync = sync, hosts = sync.hosts) }
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
    val terminalText: String = "",
    val terminalInput: String = "",
    val terminalStatus: String = "Detached",
    val terminalConnected: Boolean = false,
)

enum class PortalTab {
    Setup,
    Hosts,
    Sessions,
    Sync,
    Vault,
    Terminal,
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
    Scaffold(
        topBar = {
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
        },
        bottomBar = {
            if (state.signedInUser != null) {
                NavigationBar {
                    PortalTab.entries.filter { it != PortalTab.Setup }.forEach { tab ->
                        NavigationBarItem(
                            selected = state.selected == tab,
                            onClick = { viewModel.select(tab) },
                            icon = {
                                Icon(
                                    imageVector = when (tab) {
                                        PortalTab.Hosts -> Icons.Filled.PlayArrow
                                        PortalTab.Sessions -> Icons.Filled.Terminal
                                        PortalTab.Sync -> Icons.Filled.CloudSync
                                        PortalTab.Vault -> Icons.Filled.Key
                                        else -> Icons.Filled.Settings
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
        Surface(Modifier.fillMaxSize().padding(padding)) {
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                StatusLine(state)
                when (state.selected) {
                    PortalTab.Setup -> SetupScreen(state, viewModel)
                    PortalTab.Hosts -> HostsScreen(state, viewModel)
                    PortalTab.Sessions -> SessionsScreen(state, viewModel)
                    PortalTab.Sync -> SyncScreen(state)
                    PortalTab.Vault -> VaultScreen(state)
                    PortalTab.Terminal -> TerminalScreen(state, viewModel)
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
private fun VaultScreen(state: PortalUiState) {
    val sync = state.sync
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SummaryRow("Encrypted keys", (sync?.vaultKeyCount ?: 0).toString())
        SummaryRow("Encrypted secrets", (sync?.vaultSecretCount ?: 0).toString())
        Text("Vault payloads are synced as Portal-encrypted blobs. This build preserves synced vault data and keeps token material in Android Keystore-backed storage.")
    }
}

@Composable
private fun TerminalScreen(state: PortalUiState, viewModel: PortalViewModel) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text(state.terminalTitle, style = MaterialTheme.typography.titleMedium)
                Text(state.terminalStatus, color = MaterialTheme.colorScheme.secondary)
            }
            TextButton(onClick = viewModel::detachTerminal, enabled = state.terminalConnected) {
                Text("Detach")
            }
        }
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color(0xFF0B1120))
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
        ) {
            Text(
                text = state.terminalText.ifBlank { "No terminal output yet." },
                color = Color(0xFFE2E8F0),
                fontFamily = FontFamily.Monospace,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = state.terminalInput,
                onValueChange = viewModel::updateTerminalInput,
                label = { Text("Input") },
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
            Button(onClick = viewModel::sendTerminalInput, enabled = state.terminalConnected) {
                Text("Send")
            }
        }
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
