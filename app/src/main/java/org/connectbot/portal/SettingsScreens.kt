package org.connectbot.portal

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Key
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.connectbot.R
import org.connectbot.util.TerminalFont

@Composable
fun SettingsScreen(state: PortalUiState, viewModel: PortalViewModel, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize()) {
        Text(
            "Settings",
            fontSize = 21.sp,
            fontWeight = FontWeight.SemiBold,
            color = PortalColors.Text,
            modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 10.dp),
        )
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 12.dp, end = 12.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            HubCard(state, viewModel)
            VaultCard(state, viewModel)
            TerminalCard(state, viewModel)
            SyncServicesCard(state, viewModel)
            AboutFooter()
        }
    }

    if (state.showAddVaultKey) {
        AddVaultKeyDialog(state, viewModel)
    }
    if (state.editVaultKeyId != null) {
        RenameVaultKeyDialog(state, viewModel)
    }
}

@Composable
private fun HubCard(state: PortalUiState, viewModel: PortalViewModel) {
    PortalCard {
        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Image(
                    painter = painterResource(R.drawable.portal_logo),
                    contentDescription = null,
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(9.dp)),
                )
                Column(Modifier.weight(1f)) {
                    Text("Portal Hub", fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = PortalColors.Text)
                    Text(
                        text = state.hubUrl.removePrefix("https://").removePrefix("http://"),
                        fontSize = 10.5.sp,
                        fontFamily = PortalMono,
                        color = PortalColors.Faint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (state.signedInUser != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        StatusDot(PortalColors.Green)
                        Text("Connected", fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, color = PortalColors.Green)
                    }
                }
            }
            val info = state.hubInfo
            Text(
                text = listOfNotNull(
                    info?.let { "${it.version} · api v${it.apiVersion}" },
                    state.signedInUser?.let { "signed in as $it" },
                    relativeSyncLabel(state.lastSyncAtMillis),
                ).joinToString(" · "),
                fontSize = 11.sp,
                color = PortalColors.Faint,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PortalOutlineButton(
                    text = "Sync now",
                    onClick = viewModel::syncNow,
                    loading = state.loading,
                    modifier = Modifier.weight(1f),
                )
                PortalOutlineButton(
                    text = "Sign out",
                    onClick = viewModel::signOut,
                    contentColor = PortalColors.Danger,
                    borderColor = PortalColors.Danger.copy(alpha = 0.25f),
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun VaultCard(state: PortalUiState, viewModel: PortalViewModel) {
    val vault = state.sync?.vault ?: HubVaultConfig()
    PortalCard {
        Row(
            Modifier.padding(start = 15.dp, end = 6.dp, top = 12.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SectionLabel("Key vault", modifier = Modifier.weight(1f))
            IconButton(onClick = viewModel::openAddVaultKey, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Filled.Add, contentDescription = "Add vault key", tint = PortalColors.Faint, modifier = Modifier.size(16.dp))
            }
        }
        Row(
            Modifier.padding(horizontal = 15.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                Icons.Filled.Key,
                contentDescription = null,
                tint = if (state.vaultSecretStored) PortalColors.Green else PortalColors.Yellow,
                modifier = Modifier.size(16.dp),
            )
            Column {
                Text(
                    text = if (state.vaultSecretStored) "Unlocked · ${state.enrollDeviceName}" else "Locked on this device",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = PortalColors.Text,
                )
                Text(
                    "Keys are managed in the Portal vault, not on this device",
                    fontSize = 10.5.sp,
                    color = PortalColors.Faint,
                    modifier = Modifier.padding(top = 1.dp),
                )
            }
        }
        if (!state.vaultSecretStored && vault.hasItems) {
            Row(Modifier.padding(horizontal = 15.dp, vertical = 4.dp)) {
                PortalOutlineButton(
                    text = if (state.vaultEnrollmentId != null) "Waiting for desktop approval…" else "Request vault access",
                    onClick = viewModel::requestVaultUnlock,
                    enabled = state.vaultEnrollmentId == null && !state.loading,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        vault.keys.forEach { key ->
            VaultKeyRow(key, state, viewModel)
        }
        Text(
            text = "Manage keys in Portal on desktop →",
            fontSize = 11.sp,
            color = PortalColors.Accent,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 15.dp, vertical = 10.dp),
        )
    }
}

@Composable
private fun VaultKeyRow(key: VaultKey, state: PortalUiState, viewModel: PortalViewModel) {
    var showDetails by remember(key.id) { mutableStateOf(false) }
    Spacer(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(PortalColors.LineSoft),
    )

    if (showDetails) {
        AlertDialog(
            onDismissRequest = { showDetails = false },
            containerColor = PortalColors.Surface,
            title = { Text(key.name, color = PortalColors.Text) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SummaryRow("Type", key.algorithm ?: "Encrypted SSH key")
                    key.fingerprint?.let { SummaryRow("Fingerprint", it) }
                    SummaryRow("Updated", key.updatedAt)
                    key.publicKey?.let {
                        Text("Public key", style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = PortalColors.Text))
                        Text(it.take(160), color = PortalColors.Muted, fontFamily = PortalMono, fontSize = 10.5.sp)
                    }
                }
            },
            confirmButton = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    PortalOutlineButton(
                        text = "Copy public key",
                        onClick = { viewModel.copyVaultPublicKey(key.id) },
                        enabled = !state.loading && !key.publicKey.isNullOrBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    PortalOutlineButton(
                        text = "Rename",
                        onClick = {
                            showDetails = false
                            viewModel.editVaultKey(key.id)
                        },
                        enabled = !state.loading,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TextButton(
                        onClick = {
                            showDetails = false
                            viewModel.deleteVaultKey(key.id)
                        },
                        enabled = !state.loading,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Delete", color = PortalColors.Danger)
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showDetails = false }) {
                    Text("Close", color = PortalColors.Muted)
                }
            },
        )
    }
}

@Composable
private fun VaultKeyRowContent(key: VaultKey, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 15.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            key.name,
            fontSize = 11.5.sp,
            fontFamily = PortalMono,
            color = PortalColors.Text,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            key.algorithm ?: "SSH KEY",
            fontSize = 10.sp,
            fontFamily = PortalMono,
            color = PortalColors.Faint,
        )
        PortalBadge("VAULT")
    }
}

@Composable
private fun TerminalCard(state: PortalUiState, viewModel: PortalViewModel) {
    var fontPickerOpen by remember { mutableStateOf(false) }
    PortalCard {
        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionLabel("Terminal")
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Font size", fontSize = 13.sp, color = PortalColors.Text, modifier = Modifier.weight(1f))
                StepperButton("−") { viewModel.updateTerminalFontSize(state.terminalFontSize - 1f) }
                Text(
                    "${state.terminalFontSize.toInt()}sp",
                    fontSize = 12.sp,
                    fontFamily = PortalMono,
                    color = PortalColors.Text,
                    modifier = Modifier.width(38.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                StepperButton("+") { viewModel.updateTerminalFontSize(state.terminalFontSize + 1f) }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.clickable { fontPickerOpen = true },
            ) {
                Text("Font", fontSize = 13.sp, color = PortalColors.Text, modifier = Modifier.weight(1f))
                Text(
                    TerminalFont.fromName(state.terminalFontFamily)?.displayName ?: state.terminalFontFamily,
                    fontSize = 12.sp,
                    color = PortalColors.Faint,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Theme", fontSize = 13.sp, color = PortalColors.Text, modifier = Modifier.weight(1f))
                Text("Portal Dark", fontSize = 12.sp, color = PortalColors.Faint)
            }
        }
    }
    if (fontPickerOpen) {
        AlertDialog(
            onDismissRequest = { fontPickerOpen = false },
            containerColor = PortalColors.Surface,
            title = { Text("Terminal font", color = PortalColors.Text) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    TerminalFont.entries.forEach { font ->
                        TextButton(
                            onClick = {
                                viewModel.updateTerminalFontFamily(font.name)
                                fontPickerOpen = false
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                font.displayName,
                                color = if (font.name.equals(state.terminalFontFamily, ignoreCase = true)) {
                                    PortalColors.Accent
                                } else {
                                    PortalColors.Text
                                },
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { fontPickerOpen = false }) {
                    Text("Close", color = PortalColors.Muted)
                }
            },
        )
    }
}

@Composable
private fun StepperButton(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        fontSize = 15.sp,
        color = PortalColors.Text,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        modifier = Modifier
            .size(30.dp)
            .background(PortalColors.SurfaceAlt, RoundedCornerShape(9.dp))
            .border(1.dp, PortalColors.LineStrong, RoundedCornerShape(9.dp))
            .clickable(onClick = onClick)
            .padding(top = 3.dp),
    )
}

@Composable
private fun SyncServicesCard(state: PortalUiState, viewModel: PortalViewModel) {
    val services = listOf(
        Triple("hosts", "Hosts", "Servers, groups and tags"),
        Triple("settings", "Settings", "Terminal + app preferences"),
        Triple("snippets", "Snippets", "Saved commands"),
        Triple("vault", "Key vault", "Encrypted key blobs only"),
        Triple("sessions", "Persistent sessions", "Keep shells alive on the Hub"),
    )
    PortalCard {
        SectionLabel("Synced services", modifier = Modifier.padding(start = 15.dp, top = 12.dp, bottom = 2.dp))
        services.forEach { (id, name, desc) ->
            ToggleRow(
                title = name,
                subtitle = desc,
                checked = state.serviceEnabled(id),
                onToggle = { viewModel.toggleService(id) },
            )
        }
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun AboutFooter() {
    Text(
        text = "Portal for Android · com.digitalpals.portal.android\nTerminal, SSH and storage code derived from ConnectBot (Apache-2.0)",
        fontSize = 10.5.sp,
        lineHeight = 16.sp,
        color = PortalColors.Dim,
        modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
    )
}

@Composable
private fun AddVaultKeyDialog(state: PortalUiState, viewModel: PortalViewModel) {
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri ->
        uri?.let(viewModel::importVaultPrivateKey)
    }
    AlertDialog(
        onDismissRequest = viewModel::cancelAddVaultKey,
        containerColor = PortalColors.Surface,
        title = { Text("Add vault key", color = PortalColors.Text) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                PortalTextField("Name", state.newVaultKeyName, viewModel::updateNewVaultKeyName)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SectionLabel("Private key (PEM)")
                    OutlinedTextField(
                        value = state.newVaultPrivateKey,
                        onValueChange = viewModel::updateNewVaultPrivateKey,
                        minLines = 4,
                        maxLines = 8,
                        textStyle = TextStyle(fontSize = 11.sp, fontFamily = PortalMono, color = PortalColors.Text),
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = PortalColors.SurfaceAlt,
                            unfocusedContainerColor = PortalColors.SurfaceAlt,
                            focusedBorderColor = PortalColors.Accent,
                            unfocusedBorderColor = PortalColors.LineStrong,
                            cursorColor = PortalColors.Accent,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                PortalOutlineButton(
                    text = "Import from file…",
                    onClick = { importLauncher.launch("*/*") },
                    modifier = Modifier.fillMaxWidth(),
                )
                state.error?.let {
                    Text(it, color = PortalColors.Danger, fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            PortalPrimaryButton(
                text = "Add key",
                onClick = viewModel::addVaultKey,
                loading = state.loading,
                modifier = Modifier.width(130.dp),
            )
        },
        dismissButton = {
            TextButton(onClick = viewModel::cancelAddVaultKey) {
                Text("Cancel", color = PortalColors.Muted)
            }
        },
    )
}

@Composable
private fun RenameVaultKeyDialog(state: PortalUiState, viewModel: PortalViewModel) {
    AlertDialog(
        onDismissRequest = viewModel::cancelEditVaultKey,
        containerColor = PortalColors.Surface,
        title = { Text("Rename key", color = PortalColors.Text) },
        text = {
            PortalTextField("Name", state.editVaultKeyName, viewModel::updateEditVaultKeyName)
        },
        confirmButton = {
            PortalPrimaryButton(
                text = "Save",
                onClick = viewModel::saveVaultKeyName,
                loading = state.loading,
                modifier = Modifier.width(110.dp),
            )
        },
        dismissButton = {
            TextButton(onClick = viewModel::cancelEditVaultKey) {
                Text("Cancel", color = PortalColors.Muted)
            }
        },
    )
}
