package org.connectbot.portal

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FloatingActionButton
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun HostsScreen(state: PortalUiState, viewModel: PortalViewModel, modifier: Modifier = Modifier) {
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }

    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.padding(start = 18.dp, end = 12.dp, top = 14.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "Hosts",
                    fontSize = 21.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = PortalColors.Text,
                    modifier = Modifier.weight(1f),
                )
                if (state.signedInUser != null) {
                    HubStatusPill()
                }
                IconButton(onClick = {
                    searchOpen = !searchOpen
                    if (!searchOpen) query = ""
                }) {
                    Icon(
                        if (searchOpen) Icons.Filled.Close else Icons.Filled.Search,
                        contentDescription = "Search",
                        tint = PortalColors.Muted,
                    )
                }
            }
            if (searchOpen) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    placeholder = { Text("Filter hosts", color = PortalColors.Dim, fontSize = 13.sp) },
                    textStyle = TextStyle(fontSize = 13.sp, color = PortalColors.Text),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = PortalColors.SurfaceAlt,
                        unfocusedContainerColor = PortalColors.SurfaceAlt,
                        focusedBorderColor = PortalColors.Accent,
                        unfocusedBorderColor = PortalColors.LineStrong,
                        cursorColor = PortalColors.Accent,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 2.dp),
                )
            }

            val groups = state.sync?.hostGroups ?: emptyList()
            val filtered = groups
                .map { group ->
                    group.copy(
                        hosts = group.hosts.filter {
                            query.isBlank() ||
                                it.name.contains(query, ignoreCase = true) ||
                                it.hostname.contains(query, ignoreCase = true)
                        },
                    )
                }
                .filter { it.hosts.isNotEmpty() }

            if (filtered.isEmpty()) {
                EmptyText(
                    text = if (query.isBlank()) {
                        "No synced hosts yet. Add one here or sync a Portal desktop profile through the Hub."
                    } else {
                        "No hosts match “$query”."
                    },
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = 12.dp,
                        end = 12.dp,
                        bottom = 96.dp,
                    ),
                ) {
                    filtered.forEach { group ->
                        item(key = "group-${group.name}") {
                            SectionLabel(
                                group.name,
                                modifier = Modifier.padding(start = 8.dp, top = 16.dp, bottom = 8.dp),
                            )
                        }
                        item(key = "card-${group.name}") {
                            PortalCard {
                                group.hosts.forEachIndexed { index, host ->
                                    HostRow(
                                        host = host,
                                        live = state.terminalTabs.any { it.hostId == host.id },
                                        onClick = { viewModel.connect(host) },
                                        onLongClick = { viewModel.editHost(host.id) },
                                    )
                                    if (index < group.hosts.lastIndex) {
                                        Spacer(
                                            Modifier
                                                .fillMaxWidth()
                                                .height(1.dp)
                                                .background(PortalColors.LineSoft),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        FloatingActionButton(
            onClick = viewModel::openNewHost,
            containerColor = PortalColors.Accent,
            contentColor = PortalColors.OnAccent,
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 18.dp, bottom = 18.dp),
        ) {
            Icon(Icons.Filled.Add, contentDescription = "New host")
        }
    }

    if (state.editHostId != null) {
        EditHostDialog(state, viewModel)
    }
}

@Composable
fun HubStatusPill(modifier: Modifier = Modifier) {
    Row(
        modifier
            .border(1.dp, PortalColors.Green.copy(alpha = 0.2f), RoundedCornerShape(100.dp))
            .background(PortalColors.Green.copy(alpha = 0.07f), RoundedCornerShape(100.dp))
            .padding(horizontal = 11.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        StatusDot(PortalColors.Green)
        Text(
            "HUB",
            fontSize = 10.5.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.4.sp,
            color = PortalColors.Green,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HostRow(
    host: PortalHost,
    live: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        HostAvatar(host.name)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Text(host.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = PortalColors.Text)
                if (live) StatusDot(PortalColors.Green)
            }
            Text(
                text = "${host.targetUser}@${host.hostname}" + if (host.port != 22) ":${host.port}" else "",
                fontSize = 11.sp,
                fontFamily = PortalMono,
                color = PortalColors.Faint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Icon(
            Icons.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = PortalColors.Dim,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
fun NewHostScreen(state: PortalUiState, viewModel: PortalViewModel, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IconButton(onClick = viewModel::closeView) {
                Icon(Icons.Filled.Close, contentDescription = "Cancel", tint = PortalColors.Muted)
            }
            Text(
                "New host",
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = PortalColors.Text,
                modifier = Modifier.weight(1f),
            )
            PortalPrimaryButton(
                text = "Save",
                onClick = viewModel::saveNewHost,
                loading = state.loading,
                modifier = Modifier.width(110.dp),
            )
        }
        Spacer(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(PortalColors.LineSoft),
        )
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            PortalTextField(
                label = "Label",
                value = state.newHostName,
                onValueChange = viewModel::updateNewHostName,
                placeholder = "media-01",
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PortalTextField(
                    label = "Address",
                    value = state.newHostAddress,
                    onValueChange = viewModel::updateNewHostAddress,
                    placeholder = "media-01.internal",
                    mono = true,
                    modifier = Modifier.weight(2.2f),
                )
                PortalTextField(
                    label = "Port",
                    value = state.newHostPort,
                    onValueChange = viewModel::updateNewHostPort,
                    mono = true,
                    keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                    modifier = Modifier.weight(0.9f),
                )
            }
            PortalTextField(
                label = "Username",
                value = state.newHostUsername,
                onValueChange = viewModel::updateNewHostUsername,
                mono = true,
                placeholder = "ops",
            )

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SectionLabel("Authentication")
                AuthPicker(state, viewModel)
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .border(1.dp, PortalColors.LineStrong, RoundedCornerShape(12.dp))
                    .background(PortalColors.SurfaceAlt, RoundedCornerShape(12.dp))
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                StatusDot(PortalColors.Green)
                Column(Modifier.weight(1f)) {
                    Text("Portal Hub", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = PortalColors.Text)
                    Text(
                        "Always on — persistent session, resume from any device",
                        fontSize = 10.5.sp,
                        color = PortalColors.Faint,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }

            state.error?.let {
                Text(it, color = PortalColors.Danger, fontSize = 12.sp, lineHeight = 17.sp)
            }
        }
    }
}

@Composable
private fun AuthPicker(state: PortalUiState, viewModel: PortalViewModel) {
    val vaultKeys = state.sync?.vault?.keys.orEmpty()
    var pickerOpen by remember { mutableStateOf(false) }
    val usesVault = state.newHostVaultKeyId != null
    AuthPickerContent(
        state = state,
        viewModel = viewModel,
        usesVault = usesVault,
        onOpenPicker = { pickerOpen = true },
    )
    if (pickerOpen) {
        AlertDialog(
            onDismissRequest = { pickerOpen = false },
            containerColor = PortalColors.Surface,
            title = { Text("Select vault key", color = PortalColors.Text) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    vaultKeys.forEach { vaultKey ->
                        TextButton(
                            onClick = {
                                viewModel.updateNewHostVaultKeyId(vaultKey.id)
                                pickerOpen = false
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(vaultKey.name, color = PortalColors.Text)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { pickerOpen = false }) {
                    Text("Cancel", color = PortalColors.Muted)
                }
            },
        )
    }
}

@Composable
private fun AuthPickerContent(
    state: PortalUiState,
    viewModel: PortalViewModel,
    usesVault: Boolean,
    onOpenPicker: () -> Unit,
) {
    val vaultKeys = state.sync?.vault?.keys.orEmpty()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .border(1.dp, PortalColors.LineStrong, RoundedCornerShape(12.dp))
                .background(PortalColors.SurfaceAlt, RoundedCornerShape(12.dp))
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            AuthOption(
                label = "Vault key",
                selected = usesVault,
                enabled = vaultKeys.isNotEmpty(),
                onClick = { viewModel.updateNewHostVaultKeyId(vaultKeys.firstOrNull()?.id) },
                modifier = Modifier.weight(1f),
            )
            AuthOption(
                label = "Prompt in shell",
                selected = !usesVault,
                enabled = true,
                onClick = { viewModel.updateNewHostVaultKeyId(null) },
                modifier = Modifier.weight(1f),
            )
        }
        if (usesVault) {
            val key = vaultKeys.firstOrNull { it.id == state.newHostVaultKeyId }
            Row(
                Modifier
                    .fillMaxWidth()
                    .border(1.dp, PortalColors.LineStrong, RoundedCornerShape(12.dp))
                    .background(PortalColors.SurfaceAlt, RoundedCornerShape(12.dp))
                    .clickable(onClick = onOpenPicker)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    androidx.compose.material.icons.Icons.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = PortalColors.Accent,
                    modifier = Modifier.size(15.dp),
                )
                Column(Modifier.weight(1f)) {
                    Text(key?.name ?: "Choose a vault key", fontSize = 12.sp, fontFamily = PortalMono, color = PortalColors.Text)
                    Text(
                        "From Portal vault · sent to Hub only for the session",
                        fontSize = 10.5.sp,
                        color = PortalColors.Faint,
                        modifier = Modifier.padding(top = 1.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun AuthOption(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .background(
                if (selected) PortalColors.Accent else androidx.compose.ui.graphics.Color.Transparent,
                RoundedCornerShape(9.dp),
            )
            .combinedClickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = when {
                selected -> PortalColors.OnAccent
                enabled -> PortalColors.Muted
                else -> PortalColors.Dim
            },
        )
    }
}

@Composable
fun EditHostDialog(state: PortalUiState, viewModel: PortalViewModel) {
    var showVaultKeyPicker by remember(state.editHostId) { mutableStateOf(false) }
    val vaultKeys = state.sync?.vault?.keys.orEmpty()
    val selectedVaultKey = vaultKeys.firstOrNull { it.id == state.editHostVaultKeyId }

    AlertDialog(
        onDismissRequest = viewModel::cancelEditHost,
        containerColor = PortalColors.Surface,
        title = { Text("Edit host", color = PortalColors.Text) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                PortalTextField("Name", state.editHostName, viewModel::updateEditHostName)
                PortalTextField("Username", state.editHostUsername, viewModel::updateEditHostUsername, mono = true)
                PortalTextField("Host", state.editHostHostname, viewModel::updateEditHostHostname, mono = true)
                PortalTextField(
                    "Port",
                    state.editHostPort,
                    viewModel::updateEditHostPort,
                    mono = true,
                    keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                )
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    SectionLabel("Vault key")
                    PortalOutlineButton(
                        text = selectedVaultKey?.name ?: "No vault key",
                        onClick = { showVaultKeyPicker = true },
                        enabled = vaultKeys.isNotEmpty() || state.editHostVaultKeyId != null,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    selectedVaultKey?.fingerprint?.let {
                        Text(it, fontSize = 10.5.sp, fontFamily = PortalMono, color = PortalColors.Faint)
                    }
                }
                state.error?.let {
                    Text(it, color = PortalColors.Danger, fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            PortalPrimaryButton(
                text = "Save",
                onClick = viewModel::saveHostDetails,
                loading = state.loading,
                modifier = Modifier.width(110.dp),
            )
        },
        dismissButton = {
            TextButton(onClick = viewModel::cancelEditHost) {
                Text("Cancel", color = PortalColors.Muted)
            }
        },
    )

    if (showVaultKeyPicker) {
        AlertDialog(
            onDismissRequest = { showVaultKeyPicker = false },
            containerColor = PortalColors.Surface,
            title = { Text("Select vault key", color = PortalColors.Text) },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    TextButton(
                        onClick = {
                            viewModel.updateEditHostVaultKeyId(null)
                            showVaultKeyPicker = false
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("No vault key", color = PortalColors.Muted)
                    }
                    vaultKeys.forEach { key ->
                        TextButton(
                            onClick = {
                                viewModel.updateEditHostVaultKeyId(key.id)
                                showVaultKeyPicker = false
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(key.name, color = PortalColors.Text)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showVaultKeyPicker = false }) {
                    Text("Cancel", color = PortalColors.Muted)
                }
            },
        )
    }
}
