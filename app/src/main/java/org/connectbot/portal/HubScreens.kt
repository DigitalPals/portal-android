package org.connectbot.portal

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
private fun TabHeader(title: String, subtitle: String?) {
    Column(Modifier.padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 10.dp)) {
        Text(title, fontSize = 21.sp, fontWeight = FontWeight.SemiBold, color = PortalColors.Text)
        if (subtitle != null) {
            Text(subtitle, fontSize = 11.5.sp, color = PortalColors.Faint, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

// --- Sessions ---

@Composable
fun SessionsScreen(state: PortalUiState, viewModel: PortalViewModel, modifier: Modifier = Modifier) {
    var sessionToKill by remember { mutableStateOf<HubSession?>(null) }
    Column(modifier.fillMaxSize()) {
        TabHeader("Sessions", "Running on the Hub · survive disconnects")
        if (state.sessions.isEmpty()) {
            EmptyText("No active Portal Hub sessions.", Modifier.padding(horizontal = 12.dp))
        } else {
            LazyColumn(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 12.dp,
                    end = 12.dp,
                    bottom = 96.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(state.sessions, key = { it.sessionId }) { session ->
                    val attached = state.terminalTabs.any { it.id == session.sessionId }
                    SessionCard(
                        session = session,
                        attached = attached,
                        onOpen = { viewModel.resume(session) },
                        onEnd = { sessionToKill = session },
                    )
                }
                item {
                    Text(
                        "Ending a session signals the dtach process on the Hub",
                        fontSize = 11.sp,
                        color = PortalColors.Dim,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(10.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
        }
    }
    sessionToKill?.let { session ->
        AlertDialog(
            onDismissRequest = { sessionToKill = null },
            containerColor = PortalColors.Surface,
            title = { Text("End session", color = PortalColors.Text) },
            text = {
                Text(
                    "End ${session.targetUser}@${session.targetHost}:${session.targetPort} on the Hub? The remote shell will be terminated.",
                    color = PortalColors.Muted,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        sessionToKill = null
                        viewModel.killSession(session)
                    },
                    enabled = !state.loading,
                ) {
                    Text("End session", color = PortalColors.Danger)
                }
            },
            dismissButton = {
                TextButton(onClick = { sessionToKill = null }) {
                    Text("Cancel", color = PortalColors.Muted)
                }
            },
        )
    }
}

@Composable
private fun SessionCard(
    session: HubSession,
    attached: Boolean,
    onOpen: () -> Unit,
    onEnd: () -> Unit,
) {
    PortalCard {
        if (session.previewLines.isNotEmpty()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(PortalColors.SurfaceDeep, RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                session.previewLines.forEach { line ->
                    Text(
                        text = line,
                        fontSize = 9.sp,
                        fontFamily = PortalMono,
                        color = PortalColors.Muted,
                        maxLines = 1,
                        overflow = TextOverflow.Clip,
                        softWrap = false,
                    )
                }
            }
            Spacer(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(PortalColors.LineSoft),
            )
        }
        Row(
            Modifier.padding(horizontal = 13.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = session.displayName ?: "${session.targetUser}@${session.targetHost}",
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = PortalColors.Text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (attached) "attached · this device" else "detached · running on Hub",
                    fontSize = 10.5.sp,
                    color = if (attached) PortalColors.Green else PortalColors.Yellow,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Text(
                text = if (attached) "Open" else "Resume",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = PortalColors.OnAccent,
                modifier = Modifier
                    .background(PortalColors.Accent, RoundedCornerShape(100.dp))
                    .clickable(onClick = onOpen)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
            IconButton(
                onClick = onEnd,
                modifier = Modifier
                    .size(32.dp)
                    .border(1.dp, PortalColors.LineStrong, RoundedCornerShape(100.dp)),
            ) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "End session on Hub",
                    tint = PortalColors.Faint,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

// --- Snippets ---

@Composable
fun SnippetsScreen(state: PortalUiState, viewModel: PortalViewModel, modifier: Modifier = Modifier) {
    val snippets = state.sync?.snippets.orEmpty()
    Column(modifier.fillMaxSize()) {
        TabHeader("Snippets", "Synced from your vault profile")
        if (snippets.isEmpty()) {
            EmptyText(
                "No synced snippets. Create snippets in Portal on desktop and they sync here.",
                Modifier.padding(horizontal = 12.dp),
            )
        } else {
            LazyColumn(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 12.dp,
                    end = 12.dp,
                    bottom = 96.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(snippets, key = { it.id }) { snippet ->
                    SnippetRow(snippet) { viewModel.runSnippet(snippet) }
                }
            }
        }
    }
}

@Composable
private fun SnippetRow(snippet: PortalSnippet, onRun: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .border(1.dp, PortalColors.Line, RoundedCornerShape(14.dp))
            .background(PortalColors.Surface, RoundedCornerShape(14.dp))
            .clickable(onClick = onRun)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(snippet.name, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = PortalColors.Text)
            Text(
                text = "$ ${snippet.command}",
                fontSize = 10.5.sp,
                fontFamily = PortalMono,
                color = PortalColors.Accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        Icon(
            Icons.Filled.Terminal,
            contentDescription = "Run snippet",
            tint = PortalColors.Faint,
            modifier = Modifier.size(17.dp),
        )
    }
}

// Bottom sheets for snippet execution: either pick a snippet for the active
// terminal, or pick a target session for a chosen snippet.
@Composable
fun SnippetSheets(state: PortalUiState, viewModel: PortalViewModel, modifier: Modifier = Modifier) {
    val pending = state.snippetPendingRun
    if (pending != null) {
        SheetScaffold(title = "Run on session…", onDismiss = viewModel::dismissSnippetSheets, modifier = modifier) {
            state.terminalTabs.forEach { tab ->
                SheetRow(
                    title = tab.title,
                    subtitle = tab.status.lowercase(),
                    dotColor = if (tab.connected) PortalColors.Green else PortalColors.Yellow,
                    onClick = { viewModel.runSnippetOn(pending, tab.id) },
                )
            }
        }
    } else if (state.showSnippetPicker) {
        val snippets = state.sync?.snippets.orEmpty()
        SheetScaffold(title = "Run a snippet in this session", onDismiss = viewModel::dismissSnippetSheets, modifier = modifier) {
            if (snippets.isEmpty()) {
                Text(
                    "No synced snippets yet.",
                    fontSize = 12.sp,
                    color = PortalColors.Faint,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                )
            }
            snippets.forEach { snippet ->
                SheetRow(
                    title = snippet.name,
                    subtitle = "$ ${snippet.command}",
                    dotColor = PortalColors.Accent,
                    onClick = {
                        state.activeTab?.let { viewModel.runSnippetOn(snippet, it.id) }
                    },
                )
            }
        }
    }
}

@Composable
fun SheetScaffold(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    androidx.compose.foundation.layout.Box(
        modifier
            .fillMaxSize()
            .background(androidx.compose.ui.graphics.Color(0x99030508))
            .clickable(onClick = onDismiss),
    ) {
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .border(1.dp, PortalColors.LineStrong, RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
                .background(PortalColors.SurfaceAlt, RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
                .clickable(enabled = false, onClick = {})
                .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Spacer(
                Modifier
                    .align(Alignment.CenterHorizontally)
                    .size(width = 36.dp, height = 4.dp)
                    .background(PortalColors.LineStrong, RoundedCornerShape(2.dp)),
            )
            Text(
                title,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = PortalColors.Text,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            content()
        }
    }
}

@Composable
private fun SheetRow(
    title: String,
    subtitle: String,
    dotColor: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .border(1.dp, PortalColors.LineSoft, RoundedCornerShape(12.dp))
            .background(androidx.compose.ui.graphics.Color(0xFF0E1320), RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        StatusDot(dotColor)
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 12.5.sp, fontWeight = FontWeight.Medium, color = PortalColors.Text)
            Text(
                subtitle,
                fontSize = 10.sp,
                fontFamily = PortalMono,
                color = PortalColors.Accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = PortalColors.Faint,
            modifier = Modifier.size(15.dp),
        )
    }
}

// --- Ports ---

@Composable
fun PortsScreen(modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize()) {
        TabHeader("Port forwarding", "Rules ride the Hub web transport")
        PortalCard(modifier = Modifier.padding(horizontal = 12.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Not available yet",
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = PortalColors.Text,
                )
                Text(
                    text = "This Portal Hub build does not expose a port-forwarding API. " +
                        "Local and dynamic forwards will appear here once the Hub web transport supports them.",
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    color = PortalColors.Muted,
                )
            }
        }
    }
}
