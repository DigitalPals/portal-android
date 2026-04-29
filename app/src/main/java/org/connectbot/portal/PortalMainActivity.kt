package org.connectbot.portal

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.ViewModelProvider
import androidx.compose.ui.platform.LocalUriHandler

class PortalMainActivity : ComponentActivity() {
    private lateinit var viewModel: PortalViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        viewModel = ViewModelProvider(this)[PortalViewModel::class.java]
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
