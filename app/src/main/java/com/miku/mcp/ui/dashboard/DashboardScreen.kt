package com.miku.mcp.ui.dashboard

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryAlert
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.miku.mcp.security.SecretStore
import com.miku.mcp.server.TokenStore
import com.miku.mcp.service.BatteryOptimization
import com.miku.mcp.service.ServerState
import com.miku.mcp.service.ServerStatus
import com.miku.mcp.service.ServiceController
import com.miku.mcp.service.TunnelStatus
import com.miku.mcp.settings.AppSettings
import com.miku.mcp.ui.common.copyToClipboard
import kotlinx.coroutines.launch

// Overall health shown in the hero card at the top
private enum class Overall(val title: String, val icon: ImageVector) {
    ONLINE("Online", Icons.Rounded.CloudDone),
    LOCAL("Local only", Icons.Rounded.Dns),
    STARTING("Starting", Icons.Rounded.Dns),
    OFFLINE("Offline", Icons.Rounded.CloudOff),
    ATTENTION("Needs attention", Icons.Rounded.ErrorOutline)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(onOpenTunnelSettings: () -> Unit, onOpenLogs: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val serverStatus by ServerState.status.collectAsState()
    val tunnelStatus by ServerState.tunnel.collectAsState()

    // Read once per visit (the tunnel settings screen is a separate destination)
    val accessKey = remember { TokenStore(context).getOrCreate() }
    val port = remember { AppSettings(context).port }
    val tunnelModeLabel = remember {
        if (SecretStore(context).get(SecretStore.TUNNEL_MODE) == "named") "Named tunnel" else "Quick tunnel"
    }

    val snackbar = remember { SnackbarHostState() }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    // Battery optimization state; re-read when the user returns from the system screen
    var batteryUnrestricted by remember { mutableStateOf(BatteryOptimization.isIgnoring(context)) }
    val batteryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        batteryUnrestricted = BatteryOptimization.isIgnoring(context)
    }

    // Asks for the notification permission first (Android 13+), then starts the server either way
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        ServiceController.startServer(context)
    }
    val startServer: () -> Unit = {
        val needsPermission = Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        if (needsPermission) notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        else ServiceController.startServer(context)
    }

    // Copy helper: Android 13+ shows its own confirmation, older versions get a snackbar
    val copy: (String, String, Boolean) -> Unit = { label, value, sensitive ->
        copyToClipboard(context, label, value, sensitive)
        if (Build.VERSION.SDK_INT < 33) scope.launch { snackbar.showSnackbar("$label copied") }
    }

    // Derived flags used by the cards
    val serverRunning = serverStatus is ServerStatus.Running
    val serverStarting = serverStatus is ServerStatus.Starting
    val tunnelConnecting = tunnelStatus is TunnelStatus.Connecting
    val tunnelConnected = tunnelStatus is TunnelStatus.Connected
    val connectorUrl = (tunnelStatus as? TunnelStatus.Connected)?.let { "${it.url}/mcp" }

    val overall = when {
        serverStatus is ServerStatus.Error || tunnelStatus is TunnelStatus.Error -> Overall.ATTENTION
        serverRunning && tunnelConnected -> Overall.ONLINE
        serverRunning -> Overall.LOCAL
        serverStarting -> Overall.STARTING
        else -> Overall.OFFLINE
    }
    val heroSubtitle = when (overall) {
        Overall.ONLINE -> "Claude can reach your phone"
        Overall.LOCAL -> "Start the tunnel so Claude can connect"
        Overall.STARTING -> "Starting the server..."
        Overall.OFFLINE -> "Start the server to begin"
        Overall.ATTENTION -> (serverStatus as? ServerStatus.Error)?.message
            ?: (tunnelStatus as? TunnelStatus.Error)?.message.orEmpty()
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            // Large app bar that collapses while scrolling
            LargeTopAppBar(
                title = { Text("AI MCP") },
                actions = {
                    IconButton(onClick = onOpenLogs) { Icon(Icons.Rounded.Terminal, contentDescription = "Logs") }
                },
                scrollBehavior = scrollBehavior
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 24.dp
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { StatusHeroCard(overall, heroSubtitle) }

            // Server control
            item {
                ControlCard(
                    icon = Icons.Rounded.Dns,
                    title = "Server",
                    status = when (val s = serverStatus) {
                        ServerStatus.Stopped -> "Stopped"
                        ServerStatus.Starting -> "Starting..."
                        is ServerStatus.Running -> "Running"
                        is ServerStatus.Error -> s.message
                    },
                    statusIsError = serverStatus is ServerStatus.Error,
                    detail = "127.0.0.1:$port",
                    busy = serverStarting,
                    running = serverRunning || serverStarting,
                    actionEnabled = !serverStarting,
                    onToggle = { if (serverRunning) ServiceController.stopServer(context) else startServer() }
                )
            }

            // Tunnel control (independent from the server, but needs it to be running)
            item {
                ControlCard(
                    icon = Icons.Rounded.CloudDone,
                    title = "Tunnel",
                    status = when (val t = tunnelStatus) {
                        TunnelStatus.Off -> if (serverRunning) "Stopped" else "Waiting for the server"
                        TunnelStatus.Connecting -> "Connecting..."
                        is TunnelStatus.Connected -> "Connected"
                        is TunnelStatus.Error -> t.message
                    },
                    statusIsError = tunnelStatus is TunnelStatus.Error,
                    detail = tunnelModeLabel,
                    busy = tunnelConnecting,
                    running = tunnelConnected || tunnelConnecting,
                    // Starting needs a running server; stopping (or cancelling) is always allowed
                    actionEnabled = tunnelConnected || tunnelConnecting || serverRunning,
                    onToggle = {
                        if (tunnelConnected || tunnelConnecting) ServiceController.stopTunnel(context)
                        else ServiceController.startTunnel(context)
                    }
                ) {
                    TextButton(onClick = onOpenTunnelSettings) {
                        Icon(Icons.Rounded.Settings, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                        Text("Tunnel settings")
                    }
                }
            }

            // URL and key, each with a copy button
            item {
                ConnectionCard(
                    connectorUrl = connectorUrl,
                    localUrl = "http://127.0.0.1:$port/mcp",
                    accessKey = accessKey,
                    onCopy = copy
                )
            }

            // Shown only while Android may kill the server in the background
            if (!batteryUnrestricted) {
                item {
                    BatteryHintCard(onFix = {
                        try {
                            batteryLauncher.launch(BatteryOptimization.requestIntent(context))
                        } catch (e: ActivityNotFoundException) {
                            // Some phones do not offer the direct dialog: open the general list instead
                            batteryLauncher.launch(BatteryOptimization.settingsIntent())
                        }
                    })
                }
            }
        }
    }
}

// Big colored card summarizing the state of the whole system
@Composable
private fun StatusHeroCard(overall: Overall, subtitle: String) {
    val colors = MaterialTheme.colorScheme
    val (container, content) = when (overall) {
        Overall.ONLINE -> colors.primaryContainer to colors.onPrimaryContainer
        Overall.LOCAL, Overall.STARTING -> colors.secondaryContainer to colors.onSecondaryContainer
        Overall.OFFLINE -> colors.surfaceContainerHigh to colors.onSurface
        Overall.ATTENTION -> colors.errorContainer to colors.onErrorContainer
    }
    // Colors animate when the state changes
    val animatedContainer by animateColorAsState(container, label = "heroContainer")
    val animatedContent by animateColorAsState(content, label = "heroContent")

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = animatedContainer, contentColor = animatedContent)
    ) {
        Row(
            modifier = Modifier.padding(24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Round badge behind the status icon
            Surface(
                shape = CircleShape,
                color = animatedContent.copy(alpha = 0.12f),
                modifier = Modifier.size(56.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(overall.icon, contentDescription = null, modifier = Modifier.size(28.dp))
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(overall.title, style = MaterialTheme.typography.headlineSmall)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

// One card per component (server / tunnel): icon, state, and a Start/Stop button
@Composable
private fun ControlCard(
    icon: ImageVector,
    title: String,
    status: String,
    statusIsError: Boolean,
    detail: String,
    busy: Boolean,              // shows an indeterminate progress bar
    running: Boolean,           // decides between the Start and the Stop button
    actionEnabled: Boolean,
    onToggle: () -> Unit,
    extra: @Composable ColumnScope.() -> Unit = {}   // optional content under the main row
) {
    val colors = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = colors.surfaceContainerLow)
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // The badge is tinted when the component is active
                Surface(
                    shape = CircleShape,
                    color = if (running) colors.primaryContainer else colors.surfaceContainerHighest,
                    modifier = Modifier.size(44.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            icon, contentDescription = null,
                            tint = if (running) colors.onPrimaryContainer else colors.onSurfaceVariant
                        )
                    }
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        status,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (statusIsError) colors.error else colors.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant
                    )
                }

                if (running) {
                    FilledTonalButton(onClick = onToggle, enabled = actionEnabled) {
                        Icon(Icons.Rounded.Stop, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                        Text("Stop")
                    }
                } else {
                    Button(onClick = onToggle, enabled = actionEnabled) {
                        Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                        Text("Start")
                    }
                }
            }

            if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            extra()
        }
    }
}

// Public URL, local URL and access key, each in its own row with a copy button
@Composable
private fun ConnectionCard(
    connectorUrl: String?,
    localUrl: String,
    accessKey: String,
    onCopy: (label: String, value: String, sensitive: Boolean) -> Unit
) {
    var keyVisible by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Connection", style = MaterialTheme.typography.titleMedium)

            // The URL to paste into Claude (only exists while the tunnel is connected)
            ValueRow(
                label = "Connector URL",
                value = connectorUrl ?: "Start the tunnel to get a public URL",
                enabled = connectorUrl != null,
                onCopy = { connectorUrl?.let { onCopy("Connector URL", it, false) } }
            )

            ValueRow(
                label = "Local URL",
                value = localUrl,
                enabled = true,
                onCopy = { onCopy("Local URL", localUrl, false) }
            )

            // The access key is hidden until the user reveals it
            ValueRow(
                label = "Access key",
                value = if (keyVisible) accessKey else "•".repeat(24),
                enabled = true,
                onCopy = { onCopy("Access key", accessKey, true) },
                extraAction = {
                    IconButton(onClick = { keyVisible = !keyVisible }) {
                        Icon(
                            if (keyVisible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                            contentDescription = if (keyVisible) "Hide key" else "Show key"
                        )
                    }
                }
            )

            Text(
                "Add the connector URL in Claude under Settings > Connectors. When asked to approve, enter the access key.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// A rounded row: small label above a monospace value, with action buttons at the end
@Composable
private fun ValueRow(
    label: String,
    value: String,
    enabled: Boolean,
    onCopy: () -> Unit,
    extraAction: @Composable () -> Unit = {}
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = MaterialTheme.shapes.large,
        color = colors.surfaceContainerHighest,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                Text(
                    value,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    color = if (enabled) colors.onSurface else colors.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            extraAction()
            IconButton(onClick = onCopy, enabled = enabled) {
                Icon(Icons.Rounded.ContentCopy, contentDescription = "Copy $label")
            }
        }
    }
}

// Warning card shown only while Android may stop the server in the background
@Composable
private fun BatteryHintCard(onFix: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(
            containerColor = colors.tertiaryContainer,
            contentColor = colors.onTertiaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(Icons.Rounded.BatteryAlert, contentDescription = null)
                Column {
                    Text("Background running is restricted", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Android may stop the server when the screen is off.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            OutlinedButton(onClick = onFix) { Text("Allow background running") }
        }
    }
}