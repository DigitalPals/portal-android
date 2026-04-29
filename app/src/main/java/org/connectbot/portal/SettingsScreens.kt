package org.connectbot.portal

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import org.connectbot.util.TerminalFont

@Composable
fun SettingsScreen(state: PortalUiState, viewModel: PortalViewModel) {
    BackHandler(enabled = state.settingsSection != SettingsSection.Home) {
        viewModel.showSettingsHome()
    }

    when (state.settingsSection) {
        SettingsSection.Home -> SettingsHomeScreen(state, viewModel)
        SettingsSection.Terminal -> TerminalSettingsScreen(state, viewModel)
        SettingsSection.Sync -> SyncSettingsScreen(state, viewModel)
        SettingsSection.Vault -> VaultSettingsScreen(state, viewModel)
        SettingsSection.Account -> AccountSettingsScreen(state, viewModel)
    }
}

@Composable
private fun SettingsHomeScreen(state: PortalUiState, viewModel: PortalViewModel) {
    val vault = state.sync?.vault ?: HubVaultConfig()
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.padding(bottom = 4.dp)) {
                Text("Settings", style = MaterialTheme.typography.headlineSmall)
                Text("Manage Portal Hub, terminal, sync, and vault settings.", color = MaterialTheme.colorScheme.secondary)
            }
        }
        item {
            SettingsSectionRow(
                icon = Icons.Filled.Terminal,
                title = "Terminal",
                subtitle = "Font, size, and session display",
                badge = "${state.terminalFontSize.toInt()}sp",
                onClick = { viewModel.selectSettingsSection(SettingsSection.Terminal) },
            )
        }
        item {
            SettingsSectionRow(
                icon = Icons.Filled.Refresh,
                title = "Sync",
                subtitle = "${state.hosts.size} hosts, ${state.sync?.snippets?.size ?: 0} snippets",
                badge = state.sync?.let { "Loaded" } ?: "Empty",
                onClick = { viewModel.selectSettingsSection(SettingsSection.Sync) },
            )
        }
        item {
            SettingsSectionRow(
                icon = Icons.Filled.Key,
                title = "Vault",
                subtitle = "${vault.keys.size} keys, ${vault.secrets.size} secrets",
                badge = if (state.vaultSecretStored) "Unlocked" else "Locked",
                onClick = { viewModel.selectSettingsSection(SettingsSection.Vault) },
            )
        }
        item {
            SettingsSectionRow(
                icon = Icons.Filled.Person,
                title = "Account",
                subtitle = state.signedInUser ?: "Not signed in",
                badge = "Hub",
                onClick = { viewModel.selectSettingsSection(SettingsSection.Account) },
            )
        }
    }
}

@Composable
private fun SettingsSectionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    badge: String,
    onClick: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color.White),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(
            Modifier.padding(12.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                modifier = Modifier.size(44.dp),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    SessionBadge(badge)
                }
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
            }
            Icon(Icons.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
        }
    }
}

@Composable
private fun SettingsPageHeader(title: String, subtitle: String, viewModel: PortalViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 4.dp)) {
        TextButton(onClick = viewModel::showSettingsHome) {
            Text("Back to settings")
        }
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Text(subtitle, color = MaterialTheme.colorScheme.secondary)
    }
}

@Composable
private fun TerminalSettingsScreen(state: PortalUiState, viewModel: PortalViewModel) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            SettingsPageHeader("Terminal", "Choose the terminal font and size used for Portal Hub sessions.", viewModel)
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Color.White), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SummaryRow("Selected font", TerminalFont.getDisplayName(state.terminalFontFamily))
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
            Text("Font", style = MaterialTheme.typography.titleMedium)
        }
        items(TerminalFont.entries, key = { it.name }) { option ->
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (state.terminalFontFamily == option.name) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                    } else {
                        Color.White
                    },
                ),
                modifier = Modifier.fillMaxWidth().clickable { viewModel.updateTerminalFontFamily(option.name) },
            ) {
                Row(
                    Modifier.padding(12.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(option.displayName, style = MaterialTheme.typography.titleSmall)
                        Text(
                            if (option == TerminalFont.SYSTEM_DEFAULT) "Android system monospace" else "Downloadable terminal font",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                    }
                    if (state.terminalFontFamily == option.name) {
                        SessionBadge("Selected")
                    }
                }
            }
        }
    }
}

@Composable
private fun SyncSettingsScreen(state: PortalUiState, viewModel: PortalViewModel) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            SettingsPageHeader("Sync", "Synced profile data from Portal desktop through Portal Hub.", viewModel)
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Color.White), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SyncScreen(state)
                }
            }
        }
    }
}

@Composable
private fun VaultSettingsScreen(state: PortalUiState, viewModel: PortalViewModel) {
    val vault = state.sync?.vault ?: HubVaultConfig()
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) viewModel.importVaultPrivateKey(uri)
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            SettingsPageHeader("Vault", "Manage encrypted SSH keys and device vault access.", viewModel)
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
                        if (!state.vaultSecretStored && vault.hasItems) {
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
                                Text("Check now")
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
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { importLauncher.launch("*/*") },
                                enabled = !state.loading,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Icon(Icons.Filled.UploadFile, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("Import")
                            }
                            Button(
                                onClick = viewModel::addVaultKey,
                                enabled = !state.loading,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Icon(Icons.Filled.Save, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("Save")
                            }
                            TextButton(onClick = viewModel::cancelAddVaultKey, modifier = Modifier.fillMaxWidth()) {
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
private fun AccountSettingsScreen(state: PortalUiState, viewModel: PortalViewModel) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            SettingsPageHeader("Account", "Portal Hub sign-in and local device state.", viewModel)
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Color.White), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SummaryRow("Signed in as", state.signedInUser ?: "Not signed in")
                    SummaryRow("Portal Hub", state.hubUrl)
                    state.hubInfo?.let { SummaryRow("Hub version", it.version) }
                    SummaryRow("Vault unlock key", if (state.vaultSecretStored) "Stored on device" else "Not stored")
                    Button(onClick = viewModel::signOut, enabled = state.signedInUser != null, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Filled.Logout, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Log out")
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
