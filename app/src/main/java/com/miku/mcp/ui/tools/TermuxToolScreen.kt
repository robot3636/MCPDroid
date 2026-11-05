package com.miku.mcp.ui.tools

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumTopAppBar
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.miku.mcp.service.BatteryOptimization
import com.miku.mcp.tools.ModuleIds
import com.miku.mcp.tools.ToolSettings
import com.miku.mcp.tools.isModuleEnabled
import com.miku.mcp.tools.termux.PermissionState
import com.miku.mcp.tools.termux.TermuxRunner
import com.miku.mcp.tools.termux.TermuxSupport
import com.miku.mcp.ui.common.copyToClipboard
import kotlinx.coroutines.launch

// State of the "Test connection" button
private sealed interface TestState {
    data object Idle : TestState
    data object Running : TestState
    data class Done(val success: Boolean, val message: String) : TestState
}

// Guided setup and master switch of the Termux tool
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TermuxToolScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    val settings = remember { ToolSettings.get(context) }
    val config by settings.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    // Bumped whenever something outside the app may have changed (install, permission...)
    var refresh by remember { mutableIntStateOf(0) }
    val installed = remember(refresh) { TermuxSupport.detectInstalled(context) }
    val variant = remember(refresh, config.termuxPackage) { TermuxSupport.resolve(context, config.termuxPackage) }
    val permission = remember(refresh, variant) { variant?.let { TermuxSupport.permissionState(context, it.permission) } }

    val enabled = config.isModuleEnabled(ModuleIds.TERMUX)
    // The tool may only be switched on when Termux is installed and AI MCP may call it
    val ready = variant != null && permission == PermissionState.GRANTED

    var showEnableDialog by remember { mutableStateOf(false) }
    var showCustomDialog by remember { mutableStateOf(false) }
    var test by remember { mutableStateOf<TestState>(TestState.Idle) }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh++ }
    val settingsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { refresh++ }

    // Opens a link in the browser without crashing when no browser exists
    val open: (String) -> Unit = { url -> runCatching { uriHandler.openUri(url) } }
    val copy: (String, String) -> Unit = { label, text ->
        copyToClipboard(context, label, text)
        if (Build.VERSION.SDK_INT < 33) scope.launch { snackbar.showSnackbar("$label copied") }
    }
    val openTermux: () -> Unit = {
        context.packageManager.getLaunchIntentForPackage(config.termuxPackage)?.let { context.startActivity(it) }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            MediumTopAppBar(
                title = { Text("Termux") },
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
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ---------- Master switch ----------
            val colors = MaterialTheme.colorScheme
            val container = if (enabled) colors.primaryContainer else colors.surfaceContainerHigh
            val content = if (enabled) colors.onPrimaryContainer else colors.onSurface
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.extraLarge,
                colors = CardDefaults.cardColors(containerColor = container, contentColor = content)
            ) {
                ListItem(
                    leadingContent = { Icon(Icons.Rounded.Terminal, contentDescription = null) },
                    headlineContent = {
                        Text(if (enabled) "Enabled" else "Disabled", style = MaterialTheme.typography.titleMedium)
                    },
                    supportingContent = {
                        Text(
                            when {
                                enabled -> "Claude can run commands in Termux"
                                ready -> "Ready. Turn it on to let Claude run commands"
                                else -> "Finish steps 1 and 2 first"
                            }
                        )
                    },
                    trailingContent = {
                        Switch(
                            checked = enabled,
                            // Turning it off is always allowed; turning it on needs the setup and a confirmation
                            enabled = enabled || ready,
                            onCheckedChange = { on ->
                                if (on) showEnableDialog = true
                                else settings.setModuleEnabled(ModuleIds.TERMUX, false)
                            }
                        )
                    },
                    colors = ListItemDefaults.colors(
                        containerColor = Color.Transparent,
                        headlineColor = content,
                        leadingIconColor = content,
                        supportingColor = content
                    )
                )
            }

            // ---------- Step 1: the Termux app ----------
            StepCard(1, "Termux app", done = variant != null) {
                Text(
                    if (variant != null) {
                        "Found ${variant.label}${variant.versionName?.let { " $it" } ?: ""}  (${variant.packageName})"
                    } else {
                        "Termux was not found under the package ${config.termuxPackage}."
                    },
                    style = MaterialTheme.typography.bodyMedium
                )

                // Every compatible build that is installed, to pick from
                if (installed.isNotEmpty()) {
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        installed.forEach { option ->
                            FilterChip(
                                selected = option.packageName == config.termuxPackage,
                                onClick = { settings.setTermuxPackage(option.packageName) },
                                label = { Text(option.label) }
                            )
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { showCustomDialog = true }) {
                        Icon(Icons.Rounded.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("  Custom package")
                    }
                    OutlinedButton(onClick = { open(TermuxSupport.RELEASES_URL) }) {
                        Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("  Get Termux")
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { refresh++ }) {
                        Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("  Check again")
                    }
                    TextButton(onClick = { open(TermuxSupport.REPOSITORY_URL) }) { Text("Termux on GitHub") }
                }
                Text(
                    "Install Termux from its GitHub releases or from F-Droid. Apps that embed Termux under another " +
                        "package name work too: use Custom package.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // ---------- Step 2: Android permission ----------
            StepCard(2, "Permission", done = permission == PermissionState.GRANTED) {
                when {
                    variant == null -> Text("Install Termux first (step 1).", style = MaterialTheme.typography.bodyMedium)

                    permission == PermissionState.GRANTED ->
                        Text("Granted: AI MCP may run commands in Termux.", style = MaterialTheme.typography.bodyMedium)

                    permission == PermissionState.NOT_DECLARED -> Text(
                        "This Termux build asks callers for the permission ${variant.permission}, which this build of " +
                            "AI MCP does not declare. Add <uses-permission android:name=\"${variant.permission}\" /> " +
                            "to AndroidManifest.xml and rebuild the app.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )

                    else -> {
                        Text(
                            "Allow \"Run commands in Termux environment\" for AI MCP. If the dialog does not appear, " +
                                "open the app settings, then Permissions > Additional permissions.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { permissionLauncher.launch(variant.permission) }) { Text("Grant") }
                            OutlinedButton(onClick = {
                                settingsLauncher.launch(BatteryOptimization.appSettingsIntent(context))
                            }) { Text("App settings") }
                        }
                    }
                }
            }

            // ---------- Step 3: allow external apps in Termux ----------
            StepCard(3, "Allow external apps in Termux", done = config.termuxVerified) {
                Text(
                    "Copy this command, open Termux, paste it and press Enter. It turns on allow-external-apps, " +
                        "the switch Termux requires before it accepts commands from other apps, and reloads its settings.",
                    style = MaterialTheme.typography.bodyMedium
                )
                CommandBox(TermuxSupport.SETUP_COMMAND)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { copy("Setup command", TermuxSupport.SETUP_COMMAND) }) {
                        Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("  Copy command")
                    }
                    OutlinedButton(enabled = variant != null, onClick = openTermux) { Text("Open Termux") }
                }
                Text(
                    "Optional: to let commands use your files, also run ${TermuxSupport.STORAGE_COMMAND} in Termux " +
                        "and allow the storage permission.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(onClick = { copy("Storage command", TermuxSupport.STORAGE_COMMAND) }) {
                    Text("Copy ${TermuxSupport.STORAGE_COMMAND}")
                }
            }

            // ---------- Step 4: test ----------
            StepCard(4, "Test the connection", done = config.termuxVerified) {
                Text(
                    "Runs a harmless command through the same path Claude will use.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Button(
                    enabled = ready && test !is TestState.Running,
                    onClick = {
                        val current = variant ?: return@Button
                        test = TestState.Running
                        scope.launch {
                            val result = TermuxRunner.run(context, current, "echo AI_MCP_OK; uname -m", null, 15)
                            val ok = result.error == null && result.exitCode == 0 && "AI_MCP_OK" in result.stdout
                            settings.setTermuxVerified(ok)
                            test = TestState.Done(
                                success = ok,
                                message = when {
                                    ok -> "It works. Termux answered: ${result.stdout.trim().replace("\n", " ")}"
                                    result.error != null -> result.error
                                    else -> "The command ended with exit code ${result.exitCode}. ${result.stderr.take(200)}"
                                }
                            )
                        }
                    }
                ) {
                    if (test is TestState.Running) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text("  Testing...")
                    } else {
                        Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("  Test connection")
                    }
                }

                (test as? TestState.Done)?.let { done ->
                    Surface(
                        shape = MaterialTheme.shapes.large,
                        color = if (done.success) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.errorContainer,
                        contentColor = if (done.success) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onErrorContainer
                    ) {
                        Text(done.message, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            // ---------- What Claude gets ----------
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.extraLarge,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
            ) {
                Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("What Claude can do", style = MaterialTheme.typography.titleMedium)
                    Text("run_termux_command", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelLarge)
                    Text(
                        "Runs any command that Termux can run, with a time limit (60 s by default, 300 s at most), and " +
                            "gets back the exit code, stdout and stderr. Commands are not limited to your workspaces. " +
                            "Every call is listed in Activity.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    // ----- Dialogs -----

    if (showEnableDialog) {
        AlertDialog(
            onDismissRequest = { showEnableDialog = false },
            icon = { Icon(Icons.Rounded.Warning, contentDescription = null) },
            title = { Text("Let Claude run commands?") },
            text = {
                Text(
                    "Claude will be able to run any command that Termux can run: read and delete files, install " +
                        "packages and use the network. Claude's own approval prompts still apply. " +
                        "Only enable this if you trust the conversations you connect."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    settings.setModuleEnabled(ModuleIds.TERMUX, true)
                    showEnableDialog = false
                }) { Text("Enable") }
            },
            dismissButton = { TextButton(onClick = { showEnableDialog = false }) { Text("Cancel") } }
        )
    }

    if (showCustomDialog) {
        CustomPackageDialog(
            current = config.termuxPackage,
            // Only accepts packages that really expose a Termux command service
            validate = { TermuxSupport.resolve(context, it) != null },
            onSave = { name ->
                settings.setTermuxPackage(name)
                refresh++
                showCustomDialog = false
            },
            onDismiss = { showCustomDialog = false }
        )
    }
}

// A numbered card; the badge turns into a check mark when the step is done
@Composable
private fun StepCard(number: Int, title: String, done: Boolean, content: @Composable ColumnScope.() -> Unit) {
    val colors = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = colors.surfaceContainerLow)
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(
                    shape = CircleShape,
                    color = if (done) colors.primary else colors.surfaceContainerHighest,
                    contentColor = if (done) colors.onPrimary else colors.onSurfaceVariant,
                    modifier = Modifier.size(32.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        if (done) Icon(Icons.Rounded.Check, contentDescription = "Done", modifier = Modifier.size(18.dp))
                        else Text(number.toString(), style = MaterialTheme.typography.labelLarge)
                    }
                }
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
            content()
        }
    }
}

// Selectable monospace box showing a shell command
@Composable
private fun CommandBox(text: String) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHighest) {
        SelectionContainer {
            Text(
                text,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth().padding(12.dp)
            )
        }
    }
}

// Dialog to type the package name of a Termux build with another package name
@Composable
private fun CustomPackageDialog(
    current: String,
    validate: (String) -> Boolean,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf(current) }
    var error by remember { mutableStateOf<String?>(null) }
    val shapeOk = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+").matches(text.trim())

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Custom Termux package") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Package name of a Termux build or of an app that embeds Termux. The default is com.termux.",
                    style = MaterialTheme.typography.bodyMedium
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it; error = null },
                    singleLine = true,
                    isError = error != null,
                    supportingText = { error?.let { Text(it) } }
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = shapeOk,
                onClick = {
                    val name = text.trim()
                    if (validate(name)) onSave(name)
                    else error = "Not installed, or it does not offer Termux's command service"
                }
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}