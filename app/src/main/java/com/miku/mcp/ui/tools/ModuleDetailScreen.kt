package com.miku.mcp.ui.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.miku.mcp.tools.McpTool
import com.miku.mcp.tools.ModuleIds
import com.miku.mcp.tools.ToolHub
import com.miku.mcp.tools.ToolModules
import com.miku.mcp.tools.ToolSettings
import com.miku.mcp.ui.common.EmptyState

// Detail screen of one module: master switch, per-tool switches, and module-specific settings
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModuleDetailScreen(moduleId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val settings = remember { ToolSettings.get(context) }
    val registry = remember { ToolHub.registry(context) }
    val config by settings.state.collectAsState()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    val module = ToolModules.firstOrNull { it.id == moduleId }
    val tools = remember(moduleId) {
        registry.allRegistered().filter { it.module == moduleId }.map { it.tool }
    }
    val moduleOn = moduleId !in config.disabledModules

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            MediumTopAppBar(
                title = { Text(module?.title ?: "Tool") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                scrollBehavior = scrollBehavior
            )
        }
    ) { padding ->
        if (module == null) {
            EmptyState(
                icon = Icons.Rounded.Extension,
                title = "Unknown tool",
                message = "This tool module does not exist.",
                modifier = Modifier.padding(padding)
            )
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 24.dp
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Master switch for the whole module
            item {
                val colors = MaterialTheme.colorScheme
                val container = if (moduleOn) colors.primaryContainer else colors.surfaceContainerHigh
                val content = if (moduleOn) colors.onPrimaryContainer else colors.onSurface
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.extraLarge,
                    colors = CardDefaults.cardColors(containerColor = container, contentColor = content)
                ) {
                    ListItem(
                        leadingContent = { Icon(moduleIcon(module.id), contentDescription = null) },
                        headlineContent = {
                            Text(if (moduleOn) "Enabled" else "Disabled", style = MaterialTheme.typography.titleMedium)
                        },
                        supportingContent = { Text(module.description) },
                        trailingContent = {
                            Switch(checked = moduleOn, onCheckedChange = { settings.setModuleEnabled(module.id, it) })
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

            item { SectionLabel("Tools") }

            // All tools of the module in one grouped card, separated by dividers
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.extraLarge,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
                ) {
                    Column {
                        tools.forEachIndexed { index, tool ->
                            if (index > 0) HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                            ToolRow(
                                tool = tool,
                                switchedOn = tool.name !in config.disabledTools,
                                // Individual switches are frozen while the whole module is off
                                switchEnabled = moduleOn,
                                onToggle = { settings.setToolEnabled(tool.name, it) }
                            )
                        }
                    }
                }
            }

            // Module-specific sections
            when (moduleId) {
                ModuleIds.FILES -> item { WorkspacesSection() }
                ModuleIds.DOWNLOADS -> item {
                    DownloadLimitSection(
                        maxMb = config.maxDownloadMb,
                        onChange = { settings.setMaxDownloadMb(it) }
                    )
                }
            }
        }
    }
}

// One tool: technical name, title, description, safety badges, and its switch
@Composable
private fun ToolRow(tool: McpTool, switchedOn: Boolean, switchEnabled: Boolean, onToggle: (Boolean) -> Unit) {
    val colors = MaterialTheme.colorScheme
    ListItem(
        overlineContent = { Text(tool.name, fontFamily = FontFamily.Monospace) },
        headlineContent = { Text(tool.title) },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tool.description, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    // Badges tell the user at a glance what the tool can do
                    when {
                        tool.readOnly -> TagChip("Read-only", colors.tertiaryContainer, colors.onTertiaryContainer)
                        tool.destructive -> TagChip("Can modify or delete", colors.errorContainer, colors.onErrorContainer)
                        else -> TagChip("Writes", colors.secondaryContainer, colors.onSecondaryContainer)
                    }
                    if (tool.openWorld) TagChip("Uses internet", colors.secondaryContainer, colors.onSecondaryContainer)
                }
            }
        },
        trailingContent = { Switch(checked = switchedOn, onCheckedChange = onToggle, enabled = switchEnabled) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
}

// Small rounded label
@Composable
private fun TagChip(text: String, container: Color, content: Color) {
    Surface(shape = MaterialTheme.shapes.small, color = container, contentColor = content) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
        )
    }
}

@Composable
internal fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 8.dp, top = 8.dp)
    )
}
