package com.miku.mcp.ui.settings

import android.content.ActivityNotFoundException
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.BatteryAlert
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.ColorLens
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.VpnKey
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.miku.mcp.mcp.McpHandler
import com.miku.mcp.server.OAuthStore
import com.miku.mcp.server.TokenStore
import com.miku.mcp.service.BatteryOptimization
import com.miku.mcp.service.ServerState
import com.miku.mcp.service.ServerStatus
import com.miku.mcp.service.ServiceController
import com.miku.mcp.settings.AppSettings
import com.miku.mcp.settings.ThemeMode
import com.miku.mcp.settings.UiPreferences
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onOpenTunnelSettings: () -> Unit,
    onOpenLogs: () -> Unit,
    onOpenLicenses: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val snackbar = remember { SnackbarHostState() }

    val ui = remember { UiPreferences.get(context) }
    val uiState by ui.state.collectAsState()
    val appSettings = remember { AppSettings(context) }
    val serverStatus by ServerState.status.collectAsState()
    val serverRunning = serverStatus is ServerStatus.Running || serverStatus is ServerStatus.Starting

    var port by remember { mutableIntStateOf(appSettings.port) }
    var showPortDialog by remember { mutableStateOf(false) }
    var confirmDisconnect by remember { mutableStateOf(false) }
    var confirmRegenerate by remember { mutableStateOf(false) }

    // Battery optimization state; re-read when the user returns from the system screen
    var batteryUnrestricted by remember { mutableStateOf(BatteryOptimization.isIgnoring(context)) }
    val batteryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        batteryUnrestricted = BatteryOptimization.isIgnoring(context)
    }

    val versionName = remember {
        (try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        } catch (e: Exception) {
            null
        }) ?: "unknown"
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = { LargeTopAppBar(title = { Text("Settings") }, scrollBehavior = scrollBehavior) },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ---------- Appearance ----------
            SettingsGroup("Appearance") {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Theme", style = MaterialTheme.typography.titleMedium)
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        ThemeMode.entries.forEachIndexed { index, mode ->
                            SegmentedButton(
                                selected = uiState.themeMode == mode,
                                onClick = { ui.setThemeMode(mode) },
                                shape = SegmentedButtonDefaults.itemShape(index, ThemeMode.entries.size)
                            ) { Text(mode.label) }
                        }
                    }
                }
                // Dynamic color only exists on Android 12 and newer
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    SettingsItem(
                        icon = Icons.Rounded.ColorLens,
                        title = "Dynamic color",
                        subtitle = "Use colors from your wallpaper",
                        trailing = {
                            Switch(checked = uiState.dynamicColor, onCheckedChange = { ui.setDynamicColor(it) })
                        }
                    )
                }
            }

            // ---------- Server ----------
            SettingsGroup("Server") {
                SettingsItem(
                    icon = Icons.Rounded.Dns,
                    title = "Port",
                    subtitle = if (serverRunning) "$port  (stop the server to change it)" else "$port",
                    enabled = !serverRunning,
                    onClick = { showPortDialog = true }
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                SettingsItem(
                    icon = Icons.Rounded.BatteryAlert,
                    title = "Battery optimization",
                    subtitle = if (batteryUnrestricted) "Unrestricted" else "Restricted: Android may stop the server in the background",
                    onClick = {
                        try {
                            batteryLauncher.launch(
                                if (batteryUnrestricted) BatteryOptimization.settingsIntent()
                                else BatteryOptimization.requestIntent(context)
                            )
                        } catch (e: ActivityNotFoundException) {
                            batteryLauncher.launch(BatteryOptimization.settingsIntent())
                        }
                    }
                )
            }

            // ---------- Connection ----------
            SettingsGroup("Connection") {
                SettingsItem(
                    icon = Icons.Rounded.CloudDone,
                    title = "Tunnel settings",
                    subtitle = "Quick or named tunnel, token and hostname",
                    onClick = onOpenTunnelSettings
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                SettingsItem(
                    icon = Icons.Rounded.Terminal,
                    title = "Logs",
                    subtitle = "Live output of the server and the tunnel",
                    onClick = onOpenLogs
                )
            }

            // ---------- Security ----------
            SettingsGroup("Security") {
                SettingsItem(
                    icon = Icons.Rounded.LinkOff,
                    title = "Disconnect Claude",
                    subtitle = "Sign out every connected client; Claude will ask you to approve again",
                    onClick = { confirmDisconnect = true }
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                SettingsItem(
                    icon = Icons.Rounded.VpnKey,
                    title = "Regenerate access key",
                    subtitle = "Creates a new key and stops the server so the old key stops working",
                    onClick = { confirmRegenerate = true }
                )
            }

            // ---------- About ----------
            SettingsGroup("About") {
                SettingsItem(icon = Icons.Rounded.Info, title = "AI MCP", subtitle = "Version $versionName")
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                SettingsItem(icon = Icons.Rounded.Android, title = "Package", subtitle = context.packageName)
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                SettingsItem(
                    icon = Icons.Rounded.Link,
                    title = "Protocol",
                    subtitle = "Model Context Protocol ${McpHandler.SUPPORTED_VERSIONS.first()}"
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                SettingsItem(
                    icon = Icons.Rounded.Description,
                    title = "Open-source licenses",
                    subtitle = "Libraries this app is built with",
                    onClick = onOpenLicenses
                )
            }
        }
    }

    // ----- Dialogs -----

    if (showPortDialog) {
        PortDialog(
            current = port,
            onSave = { newPort ->
                appSettings.port = newPort
                port = newPort
                showPortDialog = false
            },
            onDismiss = { showPortDialog = false }
        )
    }

    if (confirmDisconnect) {
        AlertDialog(
            onDismissRequest = { confirmDisconnect = false },
            title = { Text("Disconnect Claude?") },
            text = { Text("All signed-in clients are removed. Claude will ask you to approve the connection again.") },
            confirmButton = {
                TextButton(onClick = {
                    OAuthStore.get(context).revokeAll()
                    confirmDisconnect = false
                    scope.launch { snackbar.showSnackbar("All clients were disconnected") }
                }) { Text("Disconnect") }
            },
            dismissButton = { TextButton(onClick = { confirmDisconnect = false }) { Text("Cancel") } }
        )
    }

    if (confirmRegenerate) {
        AlertDialog(
            onDismissRequest = { confirmRegenerate = false },
            title = { Text("Regenerate the access key?") },
            text = {
                Text(
                    "A new key is created, every client is disconnected, and the server stops so the old key " +
                        "cannot be used any more. Start the server again afterwards."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    TokenStore(context).rotate()
                    OAuthStore.get(context).revokeAll()
                    // The running server still holds the old key in memory, so it must stop now
                    if (serverRunning) ServiceController.stopServer(context)
                    confirmRegenerate = false
                    scope.launch { snackbar.showSnackbar("New access key created. Start the server again.") }
                }) { Text("Regenerate") }
            },
            dismissButton = { TextButton(onClick = { confirmRegenerate = false }) { Text("Cancel") } }
        )
    }
}

// A titled card that groups related rows
@Composable
private fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 8.dp, top = 8.dp)
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.extraLarge,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
        ) {
            Column(content = content)
        }
    }
}

// One row: icon, title, optional subtitle; clickable rows show an arrow unless a custom trailing is given
@Composable
private fun SettingsItem(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    val arrow: @Composable () -> Unit = {
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null)
    }
    ListItem(
        modifier = if (onClick != null && enabled) Modifier.clickable(onClick = onClick) else Modifier,
        leadingContent = { Icon(icon, contentDescription = null) },
        headlineContent = { Text(title) },
        supportingContent = if (subtitle != null) { { Text(subtitle) } } else null,
        trailingContent = trailing ?: (if (onClick != null && enabled) arrow else null),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
}

// Dialog to type a new port number
@Composable
private fun PortDialog(current: Int, onSave: (Int) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(current.toString()) }
    val value = text.toIntOrNull()
    val valid = value != null && value in 1024..65535

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Server port") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "With a named tunnel, also change the service address in the Cloudflare dashboard " +
                        "to http://localhost:<port>.",
                    style = MaterialTheme.typography.bodyMedium
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.filter(Char::isDigit).take(5) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = !valid,
                    supportingText = { Text("A number from 1024 to 65535") }
                )
            }
        },
        confirmButton = { TextButton(enabled = valid, onClick = { onSave(value!!) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}