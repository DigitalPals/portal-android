package org.connectbot.portal

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Code
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.connectbot.service.TerminalKeyListener
import org.connectbot.terminal.DelKeyMode
import org.connectbot.terminal.Terminal
import org.connectbot.terminal.VTermKey
import org.connectbot.util.rememberTerminalTypefaceFromStoredValue

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TerminalScreen(
    state: PortalUiState,
    viewModel: PortalViewModel,
    modifier: Modifier = Modifier,
) {
    val tab = state.activeTab
    val focusRequester = remember { FocusRequester() }
    val terminalTypeface = rememberTerminalTypefaceFromStoredValue(state.terminalFontFamily)
    var showSoftwareKeyboard by remember { mutableStateOf(true) }

    BackHandler {
        viewModel.closeTerminalView()
    }

    LaunchedEffect(tab?.id) {
        if (tab != null) {
            showSoftwareKeyboard = true
            focusRequester.requestFocus()
        }
    }

    val density = LocalDensity.current
    val imeHeight = with(density) { WindowInsets.ime.getBottom(density).toDp() }
    val systemImeVisible = imeHeight > 0.dp
    var hasImeBeenVisible by remember { mutableStateOf(false) }

    LaunchedEffect(systemImeVisible) {
        if (systemImeVisible) {
            hasImeBeenVisible = true
        }
        if (hasImeBeenVisible && !systemImeVisible && showSoftwareKeyboard) {
            showSoftwareKeyboard = false
        }
    }

    Column(
        modifier
            .fillMaxSize()
            .background(PortalColors.SurfaceDeep)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
            .windowInsetsPadding(WindowInsets.imeAnimationTarget)
            .navigationBarsPadding(),
    ) {
        TerminalTopBar(
            tab = tab,
            onBack = viewModel::closeTerminalView,
            onSnippets = viewModel::openSnippetPicker,
        )
        TerminalTabStrip(
            tabs = state.terminalTabs,
            activeIndex = state.activeTerminalTab,
            onSelect = viewModel::selectTerminalTab,
            onNewSession = { viewModel.select(PortalTab.Hosts) },
        )

        Box(Modifier.weight(1f)) {
            if (tab == null) {
                ConnectingOverlay("no session", "")
            } else if (!tab.connected && tab.status != "Error") {
                ConnectingOverlay(
                    message = if (tab.status.startsWith("Unlocking")) "unlocking vault key…" else "attaching via portal-hub…",
                    detail = tab.title,
                )
            } else {
                Terminal(
                    terminalEmulator = tab.session.emulator,
                    modifier = Modifier.fillMaxSize(),
                    typeface = terminalTypeface,
                    initialFontSize = state.terminalFontSize.sp,
                    keyboardEnabled = true,
                    showSoftKeyboard = showSoftwareKeyboard,
                    focusRequester = focusRequester,
                    modifierManager = tab.session.keyHandler,
                    onTerminalTap = {
                        showSoftwareKeyboard = true
                    },
                    onImeVisibilityChanged = { visible ->
                        if (!visible && showSoftwareKeyboard) {
                            showSoftwareKeyboard = false
                        }
                    },
                    delKeyMode = DelKeyMode.Delete,
                )
            }
        }

        if (tab != null) {
            ExtraKeysRow(
                keyHandler = tab.session.keyHandler,
                sendText = tab.session::sendText,
                onToggleKeyboard = { showSoftwareKeyboard = !showSoftwareKeyboard },
            )
        }
    }
}

@Composable
private fun TerminalTopBar(
    tab: PortalTerminalTab?,
    onBack: () -> Unit,
    onSnippets: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        TerminalTopBarContent(tab, onBack, onSnippets)
        Spacer(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(PortalColors.LineSoft),
        )
    }
}

@Composable
private fun TerminalTopBarContent(
    tab: PortalTerminalTab?,
    onBack: () -> Unit,
    onSnippets: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(PortalColors.Bg)
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = PortalColors.Muted)
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = tab?.title ?: "—",
                fontSize = 13.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = PortalColors.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                StatusDot(if (tab?.connected == true) PortalColors.Green else PortalColors.Yellow, size = 6)
                Text(
                    text = (tab?.status ?: "").uppercase(),
                    fontSize = 9.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.6.sp,
                    color = if (tab?.connected == true) PortalColors.Green else PortalColors.Yellow,
                )
            }
        }
        IconButton(onClick = onSnippets) {
            Icon(Icons.Filled.Code, contentDescription = "Snippets", tint = PortalColors.Muted, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun TerminalTabStrip(
    tabs: List<PortalTerminalTab>,
    activeIndex: Int,
    onSelect: (Int) -> Unit,
    onNewSession: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(PortalColors.Bg)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEachIndexed { index, tab ->
            val active = index == activeIndex
            Row(
                Modifier
                    .border(
                        1.dp,
                        if (active) PortalColors.LineSoft else androidx.compose.ui.graphics.Color.Transparent,
                        RoundedCornerShape(10.dp),
                    )
                    .background(
                        if (active) PortalColors.SurfaceDeep else androidx.compose.ui.graphics.Color.Transparent,
                        RoundedCornerShape(10.dp),
                    )
                    .clickable { onSelect(index) }
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                StatusDot(if (active) PortalColors.Green else PortalColors.Dim, size = 6)
                Text(
                    text = tab.hostLabel,
                    fontSize = 10.5.sp,
                    fontFamily = PortalMono,
                    color = if (active) PortalColors.Text else PortalColors.Faint,
                )
            }
        }
        IconButton(
            onClick = onNewSession,
            modifier = Modifier.size(28.dp),
        ) {
            Icon(Icons.Filled.Add, contentDescription = "New session", tint = PortalColors.Faint, modifier = Modifier.size(14.dp))
        }
    }
}

@Composable
private fun ConnectingOverlay(message: String, detail: String) {
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(color = PortalColors.Accent, strokeWidth = 2.5.dp, modifier = Modifier.size(26.dp))
        Spacer(Modifier.height(14.dp))
        Text(message, fontSize = 11.5.sp, fontFamily = PortalMono, color = PortalColors.Muted)
        if (detail.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(detail, fontSize = 10.5.sp, fontFamily = PortalMono, color = PortalColors.Dim)
        }
    }
}

@Composable
private fun ExtraKeysRow(
    keyHandler: TerminalKeyListener,
    sendText: (String) -> Unit,
    onToggleKeyboard: () -> Unit,
) {
    var ctrlActive by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .background(PortalColors.Bg)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ExtraKey("esc") { keyHandler.sendEscape() }
        ExtraKey("tab") { keyHandler.sendTab() }
        ExtraKey("ctrl", active = ctrlActive) {
            ctrlActive = !ctrlActive
            keyHandler.metaPress(TerminalKeyListener.CTRL_ON, true)
        }
        ExtraKey("↑") {
            keyHandler.sendPressedKey(VTermKey.UP)
            ctrlActive = false
        }
        ExtraKey("↓") {
            keyHandler.sendPressedKey(VTermKey.DOWN)
            ctrlActive = false
        }
        ExtraKey("←") {
            keyHandler.sendPressedKey(VTermKey.LEFT)
            ctrlActive = false
        }
        ExtraKey("→") {
            keyHandler.sendPressedKey(VTermKey.RIGHT)
            ctrlActive = false
        }
        ExtraKey("-") {
            sendText("-")
            ctrlActive = false
        }
        ExtraKey("/") {
            sendText("/")
            ctrlActive = false
        }
        ExtraKey("|") {
            sendText("|")
            ctrlActive = false
        }
        ExtraKey("~") {
            sendText("~")
            ctrlActive = false
        }
        ExtraKey("PgUp") { keyHandler.sendPressedKey(VTermKey.PAGEUP) }
        ExtraKey("PgDn") { keyHandler.sendPressedKey(VTermKey.PAGEDOWN) }
        ExtraKey("Home") { keyHandler.sendPressedKey(VTermKey.HOME) }
        ExtraKey("End") { keyHandler.sendPressedKey(VTermKey.END) }
        Spacer(Modifier.width(4.dp))
        ExtraKey("⌨", accent = true, onClick = onToggleKeyboard)
    }
}

@Composable
private fun ExtraKey(
    label: String,
    active: Boolean = false,
    accent: Boolean = false,
    onClick: () -> Unit,
) {
    Text(
        text = label,
        fontSize = 11.sp,
        fontFamily = PortalMono,
        fontWeight = if (accent) FontWeight.SemiBold else FontWeight.Medium,
        color = when {
            active -> PortalColors.AccentSoft
            accent -> PortalColors.Accent
            else -> PortalColors.Muted
        },
        modifier = Modifier
            .border(
                1.dp,
                if (active) PortalColors.Accent else PortalColors.LineStrong,
                RoundedCornerShape(8.dp),
            )
            .background(
                if (active) PortalColors.Accent.copy(alpha = 0.13f) else PortalColors.SurfaceAlt,
                RoundedCornerShape(8.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 7.dp),
    )
}
