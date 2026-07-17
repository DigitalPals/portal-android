package org.connectbot.portal

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.connectbot.R

@Composable
fun WelcomeScreen(state: PortalUiState, viewModel: PortalViewModel, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Image(
            painter = painterResource(R.drawable.portal_logo),
            contentDescription = "Portal",
            modifier = Modifier
                .size(84.dp)
                .clip(RoundedCornerShape(20.dp))
                .border(1.dp, PortalColors.LineStrong, RoundedCornerShape(20.dp)),
        )
        Spacer(Modifier.height(18.dp))
        Text("Portal", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = PortalColors.Text)
        Spacer(Modifier.height(10.dp))
        Text(
            text = "Persistent SSH sessions on every device, powered by Portal Hub.",
            fontSize = 14.sp,
            lineHeight = 22.sp,
            color = PortalColors.Muted,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(280.dp),
        )
        Spacer(Modifier.height(34.dp))
        WelcomeFeature(
            icon = Icons.Filled.Terminal,
            tint = PortalColors.Accent,
            text = "Start a shell, switch networks, come back — the session keeps running on your Hub.",
        )
        Spacer(Modifier.height(10.dp))
        WelcomeFeature(
            icon = Icons.Filled.Key,
            tint = PortalColors.Green,
            text = "Private keys stay in the encrypted vault — the Hub never sees them.",
        )
        Spacer(Modifier.height(34.dp))
        PortalPrimaryButton(text = "Set up Portal Hub", onClick = viewModel::startSetup)
        Spacer(Modifier.height(14.dp))
        Text("Requires a Portal Hub on your tailnet", fontSize = 11.5.sp, color = PortalColors.Faint)
        state.error?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = PortalColors.Danger, fontSize = 12.sp, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun WelcomeFeature(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
    text: String,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .border(1.dp, PortalColors.LineStrong, RoundedCornerShape(12.dp))
            .background(PortalColors.SurfaceAlt, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        Text(text, fontSize = 12.sp, lineHeight = 17.sp, color = PortalColors.Muted)
    }
}

@Composable
fun HubSetupScreen(state: PortalUiState, viewModel: PortalViewModel, modifier: Modifier = Modifier) {
    val hubChecked = state.hubInfo != null
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            IconButton(onClick = viewModel::backToWelcome) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = PortalColors.Muted)
            }
            Text("Connect to Portal Hub", fontSize = 19.sp, fontWeight = FontWeight.SemiBold, color = PortalColors.Text)
        }
        Text(
            text = "Enter the Tailscale name or Serve URL of your Hub. Portal reads /api/info to derive OAuth and proxy settings.",
            fontSize = 12.5.sp,
            lineHeight = 19.sp,
            color = PortalColors.Muted,
        )
        PortalTextField(
            label = "Host or web URL",
            value = state.hubUrl,
            onValueChange = viewModel::updateHubUrl,
            mono = true,
            keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri),
        )
        Text(
            text = "Bare hostnames default to web port 8080; a full HTTPS Serve address is used as-is.",
            fontSize = 11.sp,
            lineHeight = 16.sp,
            color = PortalColors.Faint,
        )

        if (hubChecked) {
            HubReachableCard(state.hubInfo!!)
        }
        state.error?.let {
            Text(it, color = PortalColors.Danger, fontSize = 12.sp, lineHeight = 17.sp)
        }
        Spacer(Modifier.weight(1f))
        if (!hubChecked) {
            PortalPrimaryButton(
                text = "Check connection",
                onClick = viewModel::checkHub,
                loading = state.loading,
            )
        } else {
            PortalPrimaryButton(
                text = "Sign in with browser",
                onClick = viewModel::startSignIn,
                loading = state.loading,
            )
        }
        Spacer(Modifier.height(6.dp))
    }
}

@Composable
private fun HubReachableCard(info: HubInfo) {
    PortalCard(
        background = Color(0xFF0D1A18),
        borderColor = PortalColors.Green.copy(alpha = 0.27f),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Filled.Check, contentDescription = null, tint = PortalColors.Green, modifier = Modifier.size(15.dp))
                Text("Hub reachable", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = PortalColors.Green)
                Spacer(Modifier.weight(1f))
                Text(
                    "${info.version} · api v${info.apiVersion}",
                    fontSize = 11.sp,
                    fontFamily = PortalMono,
                    color = PortalColors.Faint,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (info.syncV2) CapabilityChip("sync v2")
                if (info.webProxy) CapabilityChip("web proxy")
                if (info.keyVault) CapabilityChip("key vault")
                if (info.vaultEnrollment) CapabilityChip("enrollment")
                if (info.syncEvents) CapabilityChip("events")
            }
        }
    }
}

@Composable
fun EnrollScreen(state: PortalUiState, viewModel: PortalViewModel, modifier: Modifier = Modifier) {
    val waiting = !state.vaultSecretStored && state.vaultEnrollmentId != null
    val done = state.vaultSecretStored
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Unlock the key vault", fontSize = 19.sp, fontWeight = FontWeight.SemiBold, color = PortalColors.Text, modifier = Modifier.padding(top = 8.dp))
        Text(
            text = "Your SSH keys live in the Portal vault. This device asks an existing Portal desktop to hand it the unlock key — encrypted to this phone, so the Hub never sees it.",
            fontSize = 12.5.sp,
            lineHeight = 20.sp,
            color = PortalColors.Muted,
        )

        when {
            done -> {
                PortalCard(
                    background = Color(0xFF0D1A18),
                    borderColor = PortalColors.Green.copy(alpha = 0.27f),
                ) {
                    Row(
                        Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(Icons.Filled.Check, contentDescription = null, tint = PortalColors.Green, modifier = Modifier.size(20.dp))
                        Column {
                            Text("Vault unlocked on this device", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = PortalColors.Green)
                            Text(
                                text = state.vaultActionMessage ?: "Envelope decrypted in Android Keystore",
                                fontSize = 11.5.sp,
                                color = PortalColors.Muted,
                                modifier = Modifier.padding(top = 3.dp),
                            )
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                PortalPrimaryButton(text = "Continue", onClick = viewModel::continueFromEnroll)
            }

            waiting -> {
                PortalCard(
                    background = Color(0xFF1A170D),
                    borderColor = PortalColors.Yellow.copy(alpha = 0.27f),
                ) {
                    Row(
                        Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicator(
                            color = PortalColors.Yellow,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(18.dp),
                        )
                        Column {
                            Text("Waiting for approval", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = PortalColors.Yellow)
                            Text(
                                text = "Open Portal on your desktop → Devices → approve “${state.enrollDeviceName}”.",
                                fontSize = 11.5.sp,
                                lineHeight = 17.sp,
                                color = PortalColors.Muted,
                                modifier = Modifier.padding(top = 3.dp),
                            )
                        }
                    }
                }
                Text(
                    text = "POST /api/vault/enrollments\nstatus: ${state.vaultEnrollmentStatus ?: "pending"} · algo: RSA-OAEP-SHA256",
                    fontSize = 10.5.sp,
                    lineHeight = 17.sp,
                    fontFamily = PortalMono,
                    color = PortalColors.Faint,
                )
                Spacer(Modifier.weight(1f))
                PortalOutlineButton(
                    text = "Cancel request",
                    onClick = viewModel::resetVaultUnlockRequest,
                    contentColor = PortalColors.Muted,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            else -> {
                PortalTextField(
                    label = "Device name",
                    value = state.enrollDeviceName,
                    onValueChange = viewModel::updateEnrollDeviceName,
                )
                PortalCard(background = PortalColors.SurfaceAlt, borderColor = PortalColors.LineStrong) {
                    Text(
                        text = "How it works\n1 · Phone generates an RSA-OAEP-SHA256 keypair in Android Keystore\n2 · Hub stores the public key + pending request\n3 · Portal desktop encrypts the vault unlock key to it\n4 · Phone decrypts locally, keeps it in Keystore",
                        fontSize = 11.5.sp,
                        lineHeight = 19.sp,
                        color = PortalColors.Faint,
                        modifier = Modifier.padding(horizontal = 15.dp, vertical = 13.dp),
                    )
                }
                state.error?.let {
                    Text(it, color = PortalColors.Danger, fontSize = 12.sp, lineHeight = 17.sp)
                }
                Spacer(Modifier.weight(1f))
                PortalPrimaryButton(
                    text = "Request enrollment",
                    onClick = viewModel::requestEnrollment,
                    loading = state.loading,
                )
            }
        }
        Spacer(Modifier.height(6.dp))
    }
}

@Composable
fun ServicesScreen(state: PortalUiState, viewModel: PortalViewModel, modifier: Modifier = Modifier) {
    val services = listOf(
        Triple("hosts", "Hosts", "Servers, groups and tags"),
        Triple("settings", "Settings", "Terminal + app preferences"),
        Triple("snippets", "Snippets", "Saved commands"),
        Triple("vault", "Key vault", "Encrypted key blobs only"),
        Triple("sessions", "Persistent sessions", "Keep shells alive on the Hub"),
    )
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Choose what syncs", fontSize = 19.sp, fontWeight = FontWeight.SemiBold, color = PortalColors.Text, modifier = Modifier.padding(top = 8.dp))
        Text(
            text = "Each service tracks its own revision on the Hub. You can change this later in Settings.",
            fontSize = 12.5.sp,
            lineHeight = 19.sp,
            color = PortalColors.Muted,
        )
        PortalCard {
            services.forEachIndexed { index, (id, name, desc) ->
                ToggleRow(
                    title = name,
                    subtitle = desc,
                    checked = state.serviceEnabled(id),
                    onToggle = { viewModel.toggleService(id) },
                )
                if (index < services.lastIndex) {
                    Spacer(
                        Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(PortalColors.LineSoft),
                    )
                }
            }
        }
        Spacer(Modifier.weight(1f))
        PortalPrimaryButton(text = "Start using Portal", onClick = viewModel::finishOnboarding)
        Spacer(Modifier.height(6.dp))
    }
}
