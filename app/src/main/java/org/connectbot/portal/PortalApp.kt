package org.connectbot.portal

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun PortalTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = PortalColors.Accent,
            onPrimary = PortalColors.OnAccent,
            secondary = PortalColors.Muted,
            background = PortalColors.Bg,
            onBackground = PortalColors.Text,
            surface = PortalColors.Bg,
            onSurface = PortalColors.Text,
            surfaceVariant = PortalColors.Surface,
            onSurfaceVariant = PortalColors.Muted,
            outline = PortalColors.LineStrong,
            error = PortalColors.Danger,
        ),
        content = content,
    )
}

@Composable
fun PortalApp(viewModel: PortalViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsState()

    Surface(modifier.fillMaxSize(), color = PortalColors.Bg) {
        Box(
            Modifier
                .fillMaxSize()
                .statusBarsPadding(),
        ) {
            when (state.stage) {
                PortalStage.Welcome -> WelcomeScreen(state, viewModel)
                PortalStage.HubSetup -> HubSetupScreen(state, viewModel)
                PortalStage.Enroll -> EnrollScreen(state, viewModel)
                PortalStage.Services -> ServicesScreen(state, viewModel)
                PortalStage.App -> MainAppScaffold(state, viewModel)
            }

            // Overlays shared by every stage.
            state.hostKeyPrompt?.let { prompt ->
                HostKeySheet(prompt, viewModel)
            }
            if (state.snippetPendingRun != null || state.showSnippetPicker) {
                SnippetSheets(state, viewModel)
            }
            state.toast?.let { message ->
                PortalToast(message, Modifier.align(Alignment.BottomCenter))
            }
        }
    }
}

@Composable
private fun MainAppScaffold(state: PortalUiState, viewModel: PortalViewModel) {
    when (state.view) {
        PortalView.Terminal -> {
            TerminalScreen(state, viewModel)
            return
        }

        PortalView.NewHost -> {
            NewHostScreen(state, viewModel)
            return
        }

        null -> Unit
    }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) {
            when (state.selected) {
                PortalTab.Hosts -> HostsScreen(state, viewModel)
                PortalTab.Sessions -> SessionsScreen(state, viewModel)
                PortalTab.Snippets -> SnippetsScreen(state, viewModel)
                PortalTab.Ports -> PortsScreen()
                PortalTab.Settings -> SettingsScreen(state, viewModel)
            }
            state.error?.let {
                ErrorBanner(it, Modifier.align(Alignment.BottomCenter))
            }
        }
        PortalNavBar(state, viewModel)
    }
}

private data class NavItem(
    val tab: PortalTab,
    val label: String,
    val icon: ImageVector,
    val service: String?,
)

@Composable
private fun PortalNavBar(state: PortalUiState, viewModel: PortalViewModel) {
    val items = listOf(
        NavItem(PortalTab.Hosts, "Hosts", Icons.Filled.Dns, null),
        NavItem(PortalTab.Sessions, "Sessions", Icons.Filled.History, "sessions"),
        NavItem(PortalTab.Snippets, "Snippets", Icons.Filled.Code, "snippets"),
        NavItem(PortalTab.Ports, "Ports", Icons.Outlined.Bolt, null),
        NavItem(PortalTab.Settings, "Settings", Icons.Filled.Settings, null),
    ).filter { it.service == null || state.serviceEnabled(it.service) }

    Column(
        Modifier
            .fillMaxWidth()
            .background(PortalColors.Bg.copy(alpha = 0.96f)),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(PortalColors.LineSoft),
        )
        Row(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 6.dp, vertical = 8.dp),
        ) {
            items.forEach { item ->
                val selected = state.selected == item.tab
                Column(
                    Modifier
                        .weight(1f)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { viewModel.select(item.tab) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Box(
                        Modifier
                            .size(width = 52.dp, height = 28.dp)
                            .background(
                                if (selected) PortalColors.Accent.copy(alpha = 0.12f) else androidx.compose.ui.graphics.Color.Transparent,
                                RoundedCornerShape(100.dp),
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            item.icon,
                            contentDescription = item.label,
                            tint = if (selected) PortalColors.AccentSoft else PortalColors.Faint,
                            modifier = Modifier.size(19.dp),
                        )
                    }
                    Text(
                        item.label,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.2.sp,
                        color = if (selected) PortalColors.AccentSoft else PortalColors.Faint,
                    )
                }
            }
        }
    }
}

@Composable
private fun HostKeySheet(prompt: HostKeyPrompt, viewModel: PortalViewModel) {
    SheetScaffold(
        title = "Verify host key",
        onDismiss = { viewModel.respondHostKey(false) },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Filled.Warning, contentDescription = null, tint = PortalColors.Yellow, modifier = Modifier.size(20.dp))
            Text(
                text = if (prompt.oldFingerprint == null) {
                    "First connection to ${prompt.host}:${prompt.port} through the Hub."
                } else {
                    "HOST KEY CHANGED for ${prompt.host}:${prompt.port}!"
                },
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = if (prompt.oldFingerprint == null) PortalColors.Muted else PortalColors.Danger,
            )
        }
        Text(
            text = "Confirm the fingerprint before trusting it in the Hub's known_hosts.",
            fontSize = 12.sp,
            lineHeight = 18.sp,
            color = PortalColors.Muted,
        )
        Column(
            Modifier
                .fillMaxWidth()
                .border(1.dp, PortalColors.LineSoft, RoundedCornerShape(12.dp))
                .background(PortalColors.SurfaceDeep, RoundedCornerShape(12.dp))
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(prompt.keyType, fontSize = 10.5.sp, fontFamily = PortalMono, color = PortalColors.Faint)
            Text(
                prompt.fingerprint,
                fontSize = 10.5.sp,
                lineHeight = 17.sp,
                fontFamily = PortalMono,
                color = PortalColors.Yellow,
            )
            prompt.oldFingerprint?.let {
                Text("was: $it", fontSize = 10.5.sp, fontFamily = PortalMono, color = PortalColors.Danger)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PortalOutlineButton(
                text = "Deny",
                onClick = { viewModel.respondHostKey(false) },
                modifier = Modifier.weight(1f),
            )
            PortalPrimaryButton(
                text = "Trust & connect",
                onClick = { viewModel.respondHostKey(true) },
                modifier = Modifier.weight(1.4f),
            )
        }
    }
}

@Composable
private fun PortalToast(message: String, modifier: Modifier = Modifier) {
    Text(
        text = message,
        fontSize = 12.sp,
        color = PortalColors.Text,
        modifier = modifier
            .padding(horizontal = 16.dp)
            .padding(bottom = 92.dp)
            .fillMaxWidth()
            .border(1.dp, PortalColors.LineStrong, RoundedCornerShape(12.dp))
            .background(androidx.compose.ui.graphics.Color(0xFF1C2330), RoundedCornerShape(12.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

@Composable
private fun ErrorBanner(message: String, modifier: Modifier = Modifier) {
    Text(
        text = message,
        fontSize = 12.sp,
        color = PortalColors.Danger,
        modifier = modifier
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .fillMaxWidth()
            .border(1.dp, PortalColors.Danger.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
            .background(androidx.compose.ui.graphics.Color(0xFF1D1114), RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
    )
}
