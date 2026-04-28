package org.connectbot.portal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

@Composable
fun PortalTheme(content: @Composable () -> Unit) {
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
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = viewModel::checkHub, enabled = !state.loading, modifier = Modifier.weight(1f)) {
                Text("Check")
            }
            Button(onClick = viewModel::startSignIn, enabled = !state.loading, modifier = Modifier.weight(1f)) {
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
            val host = state.hosts.firstOrNull {
                it.hostname == session.targetHost &&
                    it.port == session.targetPort &&
                    it.targetUser == session.targetUser
            }
            val hostName = host?.name?.ifBlank { null } ?: session.targetHost
            Card(
                colors = CardDefaults.cardColors(containerColor = Color.White),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(12.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Surface(
                            modifier = Modifier.size(44.dp),
                            shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Filled.Terminal,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(hostName, style = MaterialTheme.typography.titleMedium)
                            SessionBadge(
                                text = "${session.targetUser}@${session.targetHost}:${session.targetPort}",
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        SessionDetail("Connected since", session.createdAt.toSessionTimestamp())
                        SessionDetail("Last activity", session.updatedAt.toSessionTimestamp())
                    }

                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        Button(onClick = { viewModel.resume(session) }, enabled = !state.loading) {
                            Icon(Icons.Filled.Terminal, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Open")
                        }
                    }
                }
            }
        }
    }
}
