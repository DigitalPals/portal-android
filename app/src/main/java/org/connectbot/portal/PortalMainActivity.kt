package org.connectbot.portal

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalUriHandler
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class PortalMainActivity : ComponentActivity() {
    private val viewModel: PortalViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val uriHandler = LocalUriHandler.current
            LaunchedEffect(Unit) {
                viewModel.authRequests.collect { uriHandler.openUri(it) }
            }
            PortalTheme {
                PortalApp(viewModel)
            }
        }
        handlePortalLink(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handlePortalLink(intent)
    }

    private fun handlePortalLink(intent: Intent?) {
        val uri = intent?.data ?: return
        when {
            uri.scheme == "com.digitalpals.portal.android" && uri.path == "/oauth2redirect" -> {
                viewModel.completeSignIn(uri)
            }

            PortalAndroidPairing.hubUrlFrom(uri) != null -> {
                viewModel.startPairing(uri)
            }
        }
    }
}
