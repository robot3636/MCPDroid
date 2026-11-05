package com.miku.mcp.ui.tools

import android.Manifest
import android.content.ActivityNotFoundException
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.SdStorage
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.miku.mcp.tools.FolderPicker
import com.miku.mcp.tools.StorageAccess
import com.miku.mcp.tools.ToolSettings
import com.miku.mcp.tools.Workspace
import java.io.File

// The folders Claude may work in: permission card, list with switches and a menu, and add buttons
@Composable
internal fun WorkspacesSection() {
    val context = LocalContext.current
    val settings = remember { ToolSettings.get(context) }
    val config by settings.state.collectAsState()

    var hasAccess by remember { mutableStateOf(StorageAccess.hasAllFilesAccess(context)) }
    var message by remember { mutableStateOf<String?>(null) }            // result or error dialog
    var confirmWrite by remember { mutableStateOf<Workspace?>(null) }    // asks before allowing writes
    var renaming by remember { mutableStateOf<Workspace?>(null) }
    var removing by remember { mutableStateOf<Workspace?>(null) }
    var confirmAllStorage by remember { mutableStateOf(false) }

    // Re-read the permission when the user comes back from the system screen
    val accessLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        hasAccess = StorageAccess.hasAllFilesAccess(context)
    }
    val legacyLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        hasAccess = StorageAccess.hasAllFilesAccess(context)
    }
    val requestAccess: () -> Unit = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                accessLauncher.launch(StorageAccess.appAccessIntent(context))
            } catch (e: ActivityNotFoundException) {
                // Some phones do not have the per-app page: open the general list instead
                accessLauncher.launch(StorageAccess.generalAccessIntent())
            }
        } else {
            legacyLauncher.launch(
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            )
        }
    }

    // System folder picker: converts the choice to a real path and adds it as a read-only workspace
    val folderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            val path = FolderPicker.treeUriToPath(uri)
            message = if (path == null) {
                "That location is not a regular folder on the phone. Pick a folder from the phone's own storage or an SD card."
            } else {
                settings.addWorkspace(path)   // returns an error text, or null when it worked
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionLabel("Workspaces")
        Text(
            "Claude can only see these folders. Turn the switch on to also let it create, edit and delete files in a folder.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp)
        )

        AccessCard(granted = hasAccess, onGrant = requestAccess)

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.extraLarge,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
        ) {
            if (config.workspaces.isEmpty()) {
                Text("No workspaces yet. Add a folder below.", modifier = Modifier.padding(20.dp))
            }
            config.workspaces.forEachIndexed { index, workspace ->
                if (index > 0) HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

                // One stat call per row: flags folders that vanished or lost their permission
                val accessible = remember(workspace.path, hasAccess) {
                    File(workspace.path).let { it.isDirectory && it.canRead() }
                }
                WorkspaceRow(
                    workspace = workspace,
                    accessible = accessible,
                    onWritableChange = { wantsWrite ->
                        // Turning writing ON needs a confirmation; turning it off is immediate
                        if (wantsWrite) confirmWrite = workspace
                        else settings.setWorkspaceWritable(workspace.id, false)
                    },
                    onRename = { renaming = workspace },
                    onRemove = { removing = workspace }
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { if (hasAccess) folderLauncher.launch(null) else requestAccess() },
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Rounded.CreateNewFolder, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text("Add folder")
            }
            FilledTonalButton(
                onClick = { if (hasAccess) confirmAllStorage = true else requestAccess() },
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Rounded.SdStorage, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text("All storage")
            }
        }
    }

    // ----- Dialogs -----

    // Generic result / error message
    message?.let { text ->
        AlertDialog(
            onDismissRequest = { message = null },
            title = { Text("Workspaces") },
            text = { Text(text) },
            confirmButton = { TextButton(onClick = { message = null }) { Text("OK") } }
        )
    }

    // Confirmation before granting write access
    confirmWrite?.let { workspace ->
        AlertDialog(
            onDismissRequest = { confirmWrite = null },
            icon = { Icon(Icons.Rounded.Warning, contentDescription = null) },
            title = { Text("Allow changes in \"${workspace.alias}\"?") },
            text = { Text("Claude will be able to create, edit and delete files in:\n${workspace.path}") },
            confirmButton = {
                TextButton(onClick = {
                    settings.setWorkspaceWritable(workspace.id, true)
                    confirmWrite = null
                }) { Text("Allow") }
            },
            dismissButton = { TextButton(onClick = { confirmWrite = null }) { Text("Cancel") } }
        )
    }

    // Confirmation before exposing the whole shared storage
    if (confirmAllStorage) {
        AlertDialog(
            onDismissRequest = { confirmAllStorage = false },
            icon = { Icon(Icons.Rounded.SdStorage, contentDescription = null) },
            title = { Text("Share all storage?") },
            text = {
                Text(
                    "Claude will be able to read everything on the phone's shared storage: photos, documents, " +
                        "downloads and more. It starts read-only; you can allow writing later. " +
                        "Android still keeps other apps' private data out of reach."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmAllStorage = false
                    message = settings.addWorkspace(StorageAccess.sharedStorageRoot().path, "storage")
                }) { Text("Share") }
            },
            dismissButton = { TextButton(onClick = { confirmAllStorage = false }) { Text("Cancel") } }
        )
    }

    renaming?.let { workspace ->
        RenameDialog(
            workspace = workspace,
            onRename = { newName -> settings.renameWorkspace(workspace.id, newName) },   // error text or null
            onDismiss = { renaming = null }
        )
    }

    removing?.let { workspace ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Remove \"${workspace.alias}\"?") },
            text = { Text("Claude will no longer see this folder. Your files are not deleted.") },
            confirmButton = {
                TextButton(onClick = {
                    settings.removeWorkspace(workspace.id)
                    removing = null
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } }
        )
    }
}

// Card that shows whether the "all files" permission is granted
@Composable
private fun AccessCard(granted: Boolean, onGrant: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val container = if (granted) colors.secondaryContainer else colors.tertiaryContainer
    val content = if (granted) colors.onSecondaryContainer else colors.onTertiaryContainer

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = container, contentColor = content)
    ) {
        ListItem(
            leadingContent = {
                Icon(if (granted) Icons.Rounded.CheckCircle else Icons.Rounded.Warning, contentDescription = null)
            },
            headlineContent = { Text("All files access") },
            supportingContent = {
                Text(
                    if (granted) "Granted. A workspace can point to any folder on the phone."
                    else "Needed for folders outside this app. The default \"app\" folder works without it."
                )
            },
            trailingContent = {
                if (!granted) FilledTonalButton(onClick = onGrant) { Text("Grant") }
            },
            colors = ListItemDefaults.colors(
                containerColor = Color.Transparent,
                headlineColor = content,
                leadingIconColor = content,
                supportingColor = content
            )
        )
    }
}

// One workspace: name, path, mode, write switch, and a menu with Rename and Remove
@Composable
private fun WorkspaceRow(
    workspace: Workspace,
    accessible: Boolean,
    onWritableChange: (Boolean) -> Unit,
    onRename: () -> Unit,
    onRemove: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    var menuOpen by remember { mutableStateOf(false) }

    ListItem(
        leadingContent = { Icon(Icons.Rounded.Folder, contentDescription = null) },
        headlineContent = { Text(workspace.alias) },
        supportingContent = {
            Column {
                Text(
                    workspace.path,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                when {
                    !accessible -> Text(
                        "Not accessible: the folder is missing or the permission is not granted",
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.error
                    )
                    workspace.writable -> Text(
                        "Read and write",
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.error
                    )
                    else -> Text(
                        "Read only",
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.primary
                    )
                }
            }
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = workspace.writable, onCheckedChange = onWritableChange)
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Rounded.MoreVert, contentDescription = "More options")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Rename") },
                            leadingIcon = { Icon(Icons.Rounded.Edit, contentDescription = null) },
                            onClick = { menuOpen = false; onRename() }
                        )
                        DropdownMenuItem(
                            text = { Text("Remove") },
                            leadingIcon = { Icon(Icons.Rounded.Delete, contentDescription = null) },
                            onClick = { menuOpen = false; onRemove() }
                        )
                    }
                }
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
}

// Rename dialog: onRename returns an error text (shown under the field) or null when it worked
@Composable
private fun RenameDialog(workspace: Workspace, onRename: (String) -> String?, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(workspace.alias) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename workspace") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Claude uses this name at the start of every path.", style = MaterialTheme.typography.bodyMedium)
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
            TextButton(onClick = {
                val problem = onRename(text)
                if (problem == null) onDismiss() else error = problem
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}