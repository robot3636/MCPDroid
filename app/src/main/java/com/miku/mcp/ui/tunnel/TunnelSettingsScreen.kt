package com.miku.mcp.ui.tunnel

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumTopAppBar
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.miku.mcp.security.SecretStore
import com.miku.mcp.settings.AppSettings
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TunnelSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val secrets = remember { SecretStore(context) }
    val settings = remember { AppSettings(context) }
    val snackbar = remember { SnackbarHostState() }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    // Form state (rememberSaveable keeps it across rotation)
    var named by rememberSaveable { mutableStateOf(secrets.get(SecretStore.TUNNEL_MODE) == "named") }
    var tokenInput by rememberSaveable { mutableStateOf("") }
    var tokenSaved by rememberSaveable { mutableStateOf(!secrets.get(SecretStore.CF_TOKEN).isNullOrBlank()) }
    var tokenVisible by rememberSaveable { mutableStateOf(false) }
    var hostname by rememberSaveable { mutableStateOf(secrets.get(SecretStore.CF_HOSTNAME).orEmpty()) }
    var autoStart by rememberSaveable { mutableStateOf(settings.tunnelAutoStart) }
    var showErrors by rememberSaveable { mutableStateOf(false) }

    // Named mode needs both a token (saved or typed now) and a public hostname
    val tokenMissing = named && tokenInput.isBlank() && !tokenSaved
    val hostnameMissing = named && hostname.isBlank()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            MediumTopAppBar(
                title = { Text("Tunnel settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                scrollBehavior = scrollBehavior
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Mode selector
            Text("Mode", style = MaterialTheme.typography.titleSmall)
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = !named,
                    onClick = { named = false },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
                ) { Text("Quick") }
                SegmentedButton(
                    selected = named,
                    onClick = { named = true },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
                ) { Text("Named") }
            }

            // Short explanation of the selected mode
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
            ) {
                Text(
                    text = if (named) {
                        "Named tunnel: a fixed URL on your own domain, so Claude stays connected across restarts. " +
                            "Create the tunnel in Cloudflare Zero Trust (Networks > Tunnels), then add a public hostname " +
                            "that points to http://localhost:8765."
                    } else {
                        "Quick tunnel: no account needed. The URL is random and changes every time the tunnel starts, " +
                            "so the connector must be added again in Claude."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(16.dp)
                )
            }

            // Fields that only exist for named tunnels
            if (named) {
                OutlinedTextField(
                    value = tokenInput,
                    onValueChange = { tokenInput = it },
                    label = { Text(if (tokenSaved) "Tunnel token (saved)" else "Tunnel token") },
                    supportingText = {
                        Text(
                            when {
                                showErrors && tokenMissing -> "A tunnel token is required"
                                tokenSaved -> "Leave empty to keep the saved token"
                                else -> "Paste the token or the full install command from the dashboard"
                            }
                        )
                    },
                    isError = showErrors && tokenMissing,
                    singleLine = true,
                    visualTransformation = if (tokenVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { tokenVisible = !tokenVisible }) {
                            Icon(
                                if (tokenVisible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                contentDescription = if (tokenVisible) "Hide token" else "Show token"
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = hostname,
                    onValueChange = { hostname = it },
                    label = { Text("Public hostname") },
                    supportingText = {
                        Text(if (showErrors && hostnameMissing) "A hostname is required" else "For example: mcp.example.com")
                    },
                    isError = showErrors && hostnameMissing,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // Behavior
            ListItem(
                headlineContent = { Text("Start with the server") },
                supportingContent = { Text("Open the tunnel automatically when the server starts") },
                trailingContent = { Switch(checked = autoStart, onCheckedChange = { autoStart = it }) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
            )

            Button(
                onClick = {
                    showErrors = true
                    if (tokenMissing || hostnameMissing) return@Button

                    secrets.put(SecretStore.TUNNEL_MODE, if (named) "named" else "quick")
                    // Accept the whole "cloudflared service install <token>" command pasted from the dashboard
                    val cleaned = tokenInput
                        .replace("sudo cloudflared service install ", "")
                        .replace("cloudflared.exe service install ", "")
                        .trim()
                    if (cleaned.isNotBlank()) {
                        secrets.put(SecretStore.CF_TOKEN, cleaned)
                        tokenSaved = true
                    }
                    secrets.put(SecretStore.CF_HOSTNAME, hostname.trim())
                    settings.tunnelAutoStart = autoStart
                    tokenInput = ""

                    scope.launch { snackbar.showSnackbar("Saved. Restart the tunnel to apply the changes.") }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Save") }
        }
    }
}