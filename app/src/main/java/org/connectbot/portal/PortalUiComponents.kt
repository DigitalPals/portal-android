package org.connectbot.portal

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Design tokens from the Portal Android design handoff (dark only).
object PortalColors {
    val Bg = Color(0xFF090B10)
    val Surface = Color(0xFF101521)
    val SurfaceAlt = Color(0xFF121720)
    val SurfaceDeep = Color(0xFF0A0E14)
    val Line = Color(0xFF222B3A)
    val LineSoft = Color(0xFF1A2230)
    val LineStrong = Color(0xFF2A3444)
    val Accent = Color(0xFF63D2FF)
    val AccentSoft = Color(0xFF9BE2FF)
    val OnAccent = Color(0xFF04121B)
    val Text = Color(0xFFEDF2F7)
    val Muted = Color(0xFFAAB7C8)
    val Faint = Color(0xFF7F8DA3)
    val Dim = Color(0xFF4B5B70)
    val Green = Color(0xFF15E1A5)
    val Yellow = Color(0xFFECC613)
    val Pink = Color(0xFFE32A64)
    val Danger = Color(0xFFFF8F8F)
}

val PortalMono = FontFamily.Monospace

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = TextStyle(
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.3.sp,
            color = PortalColors.Faint,
        ),
        modifier = modifier,
    )
}

@Composable
fun PortalCard(
    modifier: Modifier = Modifier,
    background: Color = PortalColors.Surface,
    borderColor: Color = PortalColors.Line,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, borderColor, RoundedCornerShape(16.dp))
            .background(background, RoundedCornerShape(16.dp)),
        content = { content() },
    )
}

@Composable
fun PortalPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    Button(
        onClick = onClick,
        enabled = enabled && !loading,
        shape = RoundedCornerShape(100.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = PortalColors.Accent,
            contentColor = PortalColors.OnAccent,
            disabledContainerColor = PortalColors.Accent.copy(alpha = 0.35f),
            disabledContentColor = PortalColors.OnAccent.copy(alpha = 0.6f),
        ),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 15.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        if (loading) {
            CircularProgressIndicator(
                color = PortalColors.OnAccent,
                strokeWidth = 2.dp,
                modifier = Modifier.size(15.dp),
            )
            Text("  ")
        }
        Text(text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun PortalOutlineButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentColor: Color = PortalColors.Text,
    borderColor: Color = PortalColors.LineStrong,
    loading: Boolean = false,
) {
    Button(
        onClick = onClick,
        enabled = enabled && !loading,
        shape = RoundedCornerShape(100.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = PortalColors.SurfaceAlt,
            contentColor = contentColor,
            disabledContainerColor = PortalColors.SurfaceAlt,
            disabledContentColor = contentColor.copy(alpha = 0.5f),
        ),
        border = androidx.compose.foundation.BorderStroke(1.dp, borderColor),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 11.dp, horizontal = 14.dp),
        modifier = modifier,
    ) {
        if (loading) {
            CircularProgressIndicator(
                color = PortalColors.Accent,
                strokeWidth = 2.dp,
                modifier = Modifier.size(12.dp),
            )
            Text("  ")
        }
        Text(text, fontSize = 12.5.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun PortalTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    mono: Boolean = false,
    readOnly: Boolean = false,
    placeholder: String? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionLabel(label)
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            readOnly = readOnly,
            singleLine = true,
            textStyle = TextStyle(
                fontSize = 14.sp,
                fontFamily = if (mono) PortalMono else null,
                color = if (readOnly) PortalColors.Muted else PortalColors.Text,
            ),
            placeholder = placeholder?.let {
                { Text(it, color = PortalColors.Dim, fontSize = 14.sp, fontFamily = if (mono) PortalMono else null) }
            },
            keyboardOptions = keyboardOptions,
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = PortalColors.SurfaceAlt,
                unfocusedContainerColor = PortalColors.SurfaceAlt,
                focusedBorderColor = PortalColors.Accent,
                unfocusedBorderColor = PortalColors.LineStrong,
                cursorColor = PortalColors.Accent,
                focusedTextColor = PortalColors.Text,
                unfocusedTextColor = PortalColors.Text,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
fun PortalSwitch(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, modifier: Modifier = Modifier) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        colors = SwitchDefaults.colors(
            checkedTrackColor = PortalColors.Accent,
            checkedThumbColor = PortalColors.OnAccent,
            checkedBorderColor = PortalColors.Accent,
            uncheckedTrackColor = PortalColors.LineStrong,
            uncheckedThumbColor = PortalColors.Faint,
            uncheckedBorderColor = PortalColors.LineStrong,
        ),
    )
}

@Composable
fun StatusDot(color: Color, modifier: Modifier = Modifier, size: Int = 7) {
    Box(
        modifier
            .size(size.dp)
            .background(color, CircleShape),
    )
}

@Composable
fun PortalBadge(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = PortalColors.AccentSoft,
    background: Color = PortalColors.Accent.copy(alpha = 0.08f),
    borderColor: Color = PortalColors.Accent.copy(alpha = 0.18f),
) {
    Text(
        text = text,
        fontSize = 9.5.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.5.sp,
        color = color,
        modifier = modifier
            .border(1.dp, borderColor, RoundedCornerShape(6.dp))
            .background(background, RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 3.dp),
    )
}

@Composable
fun CapabilityChip(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        fontSize = 10.5.sp,
        fontWeight = FontWeight.SemiBold,
        color = PortalColors.AccentSoft,
        modifier = modifier
            .border(1.dp, PortalColors.Accent.copy(alpha = 0.2f), RoundedCornerShape(100.dp))
            .background(PortalColors.Accent.copy(alpha = 0.1f), RoundedCornerShape(100.dp))
            .padding(horizontal = 9.dp, vertical = 4.dp),
    )
}

@Composable
fun HostAvatar(name: String, modifier: Modifier = Modifier, size: Int = 38) {
    Box(
        modifier = modifier
            .size(size.dp)
            .background(PortalColors.LineSoft, RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = name.take(1).uppercase(),
            color = PortalColors.AccentSoft,
            fontFamily = PortalMono,
            fontWeight = FontWeight.Bold,
            fontSize = (size * 0.42).sp,
        )
    }
}

@Composable
fun SummaryRow(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = PortalColors.Text)
        Text(
            text = value,
            color = PortalColors.Muted,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
fun EmptyText(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        color = PortalColors.Faint,
        fontSize = 12.5.sp,
        lineHeight = 19.sp,
        modifier = modifier.padding(8.dp),
    )
}

@Composable
fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, fontSize = 13.5.sp, fontWeight = FontWeight.Medium, color = PortalColors.Text)
            Text(subtitle, fontSize = 11.sp, color = PortalColors.Faint)
        }
        PortalSwitch(checked = checked, onCheckedChange = onToggle)
    }
}

fun String.toSessionTimestamp(): String {
    if (isBlank()) return "Unknown"
    return replace('T', ' ')
        .removeSuffix("Z")
        .substringBefore('.')
}

fun relativeSyncLabel(lastSyncAtMillis: Long, nowMillis: Long = System.currentTimeMillis()): String {
    if (lastSyncAtMillis <= 0L) return "not synced yet"
    val minutes = ((nowMillis - lastSyncAtMillis) / 60_000L).coerceAtLeast(0)
    return when {
        minutes < 1 -> "synced just now"
        minutes == 1L -> "synced 1 min ago"
        minutes < 60 -> "synced $minutes min ago"
        minutes < 120 -> "synced 1 h ago"
        else -> "synced ${minutes / 60} h ago"
    }
}
