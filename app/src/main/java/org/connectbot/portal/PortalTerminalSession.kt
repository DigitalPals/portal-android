package org.connectbot.portal

import android.os.Handler
import android.os.Looper
import androidx.compose.ui.graphics.Color
import org.connectbot.service.TerminalEmulatorKeyDispatcher
import org.connectbot.service.TerminalKeyListener
import org.connectbot.terminal.TerminalEmulator
import org.connectbot.terminal.TerminalEmulatorFactory

class PortalTerminalSession(
    private val onInput: (ByteArray) -> Unit,
    private val onResize: (columns: Int, rows: Int) -> Unit,
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    val emulator: TerminalEmulator = TerminalEmulatorFactory.create(
        initialRows = 24,
        initialCols = 80,
        defaultForeground = Color(0xFFE6EDF3),
        defaultBackground = Color(0xFF050607),
        onKeyboardInput = onInput,
        onBell = {},
        onResize = { dimensions ->
            onResize(dimensions.columns, dimensions.rows)
        },
        onClipboardCopy = {},
        onProgressChange = { _, _ -> },
    )

    val keyHandler = TerminalKeyListener(TerminalEmulatorKeyDispatcher(emulator))

    fun writeOutput(data: ByteArray) {
        mainHandler.post {
            emulator.writeInput(data, 0, data.size)
        }
    }
}
