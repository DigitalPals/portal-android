package org.connectbot.portal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

@Composable
fun VaultKeyCard(key: VaultKey, state: PortalUiState, viewModel: PortalViewModel) {
    var showDetails by remember(key.id) { mutableStateOf(false) }

    Card(colors = CardDefaults.cardColors(containerColor = Color.White), modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(12.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Key, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(key.name, style = MaterialTheme.typography.titleMedium)
                Text(key.algorithm ?: "Encrypted SSH key", color = MaterialTheme.colorScheme.secondary)
            }
            OutlinedButton(onClick = { showDetails = true }, enabled = !state.loading) {
                Text("Details")
            }
        }
    }

    if (showDetails) {
        AlertDialog(
            onDismissRequest = { showDetails = false },
            title = { Text(key.name) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SummaryRow("Type", key.algorithm ?: "Encrypted SSH key")
                    key.fingerprint?.let { SummaryRow("Fingerprint", it) }
                    SummaryRow("Updated", key.updatedAt)
                    key.publicKey?.let {
                        Text("Public key", style = MaterialTheme.typography.labelLarge)
                        Text(it.take(160), color = MaterialTheme.colorScheme.secondary, fontFamily = FontFamily.Monospace)
                    }
                }
            },
            confirmButton = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = { viewModel.copyVaultPublicKey(key.id) },
                        enabled = !state.loading && !key.publicKey.isNullOrBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Filled.Key, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Copy public key")
                    }
                    OutlinedButton(
                        onClick = {
                            showDetails = false
                            viewModel.editVaultKey(key.id)
                        },
                        enabled = !state.loading,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Filled.Edit, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Rename")
                    }
                    TextButton(
                        onClick = {
                            showDetails = false
                            viewModel.deleteVaultKey(key.id)
                        },
                        enabled = !state.loading,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Filled.Delete, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Delete", color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showDetails = false }) {
                    Text("Close")
                }
            },
        )
    }

    if (state.editVaultKeyId == key.id) {
        AlertDialog(
            onDismissRequest = viewModel::cancelEditVaultKey,
            title = { Text("Rename Key") },
            text = {
                OutlinedTextField(
                    value = state.editVaultKeyName,
                    onValueChange = viewModel::updateEditVaultKeyName,
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                Button(onClick = viewModel::saveVaultKeyName, enabled = !state.loading) {
                    Icon(Icons.Filled.Save, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelEditVaultKey) {
                    Text("Cancel")
                }
            }
        )
    }
}

