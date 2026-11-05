package com.miku.mcp.ui.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.miku.mcp.tools.ModuleIds
import com.miku.mcp.tools.ModuleInfo
import com.miku.mcp.tools.ToolConfig
import com.miku.mcp.tools.ToolHub
import com.miku.mcp.tools.ToolModules
import com.miku.mcp.tools.ToolSettings
import com.miku.mcp.tools.isModuleEnabled

// True when both the module and the tool are switched on. Computed from the observed config
// so the screen recomposes whenever a switch changes.
internal fun ToolConfig.allows(module: String, tool: String): Boolean =
    isModuleEnabled(module) && tool !in disabledTools
// Icon shown for each module
internal fun moduleIcon(id: String): ImageVector = when (id) {
    ModuleIds.FILES -> Icons.Rounded.Folder
    ModuleIds.DOWNLOADS -> Icons.Rounded.Download
    ModuleIds.TERMUX -> Icons.Rounded.Terminal
    else -> Icons.Rounded.Extension
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolsScreen(onOpenModule: (String) -> Unit) {
    val context = LocalContext.current
    val settings = remember { ToolSettings.get(context) }
    val registry = remember { ToolHub.registry(context) }
    val config by settings.state.collectAsState()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    // Every registered tool (switched on or not), and how many of them Claude can use right now
    val allTools = remember { registry.allRegistered() }
    val activeTotal = allTools.count { config.allows(it.module, it.tool.name) }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = { LargeTopAppBar(title = { Text("Tools") }, scrollBehavior = scrollBehavior) }
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
            item {
                Text(
                    "Claude can use $activeTotal of ${allTools.size} tools",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
            }

            items(ToolModules, key = { it.id }) { module ->
                val moduleTools = allTools.filter { it.module == module.id }
                ModuleCard(
                    module = module,
                    enabled = config.isModuleEnabled(module.id),
                    totalCount = moduleTools.size,
                    activeCount = moduleTools.count { config.allows(module.id, it.tool.name) },
                    onToggle = { on ->
                        // Termux needs the guided setup, so turning it on opens its screen instead
                        if (module.id == ModuleIds.TERMUX && on) onOpenModule(module.id)
                        else settings.setModuleEnabled(module.id, on)
                    },
                    onOpen = { onOpenModule(module.id) }
                )
            }
        }
    }
}

// One card per module: tap to open its details, use the switch to turn it on or off
@Composable
private fun ModuleCard(
    module: ModuleInfo,
    enabled: Boolean,
    totalCount: Int,
    activeCount: Int,
    onToggle: (Boolean) -> Unit,
    onOpen: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    Card(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = colors.surfaceContainerLow)
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // The badge is tinted while the module is on
            Surface(
                shape = CircleShape,
                color = if (enabled) colors.primaryContainer else colors.surfaceContainerHighest,
                modifier = Modifier.size(48.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        moduleIcon(module.id), contentDescription = null,
                        tint = if (enabled) colors.onPrimaryContainer else colors.onSurfaceVariant
                    )
                }
            }

            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(module.title, style = MaterialTheme.typography.titleMedium)
                Text(
                    module.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant
                )
                Text(
                    if (enabled) "$activeCount of $totalCount tools on" else "Off",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (enabled) colors.primary else colors.onSurfaceVariant
                )
            }

            Switch(checked = enabled, onCheckedChange = onToggle)
        }
    }
}