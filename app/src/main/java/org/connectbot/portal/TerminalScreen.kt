package org.connectbot.portal

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.connectbot.service.TerminalKeyListener
import org.connectbot.terminal.DelKeyMode
import org.connectbot.terminal.Terminal
import org.connectbot.terminal.VTermKey
import org.connectbot.ui.components.TERMINAL_KEYBOARD_HEIGHT_DP
import org.connectbot.util.rememberTerminalTypefaceFromStoredValue

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TerminalScreen(state: PortalUiState, viewModel: PortalViewModel) {
    val session = state.terminalSession
    val focusRequester = remember { FocusRequester() }
    val terminalTypeface = rememberTerminalTypefaceFromStoredValue(state.terminalFontFamily)
    var showSoftwareKeyboard by remember { mutableStateOf(true) }

    BackHandler {
        viewModel.detachTerminal()
    }

    LaunchedEffect(session) {
        if (session != null) {
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

        Box(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
                .windowInsetsPadding(WindowInsets.imeAnimationTarget)
                .navigationBarsPadding(),
        ) {
            Terminal(
                terminalEmulator = session.emulator,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = TERMINAL_KEYBOARD_HEIGHT_DP.dp),
                typeface = terminalTypeface,
                initialFontSize = state.terminalFontSize.sp,
                keyboardEnabled = true,
                showSoftKeyboard = showSoftwareKeyboard,
                focusRequester = focusRequester,
                modifierManager = session.keyHandler,
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

            PortalTerminalKeyboard(
                keyHandler = session.keyHandler,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
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
