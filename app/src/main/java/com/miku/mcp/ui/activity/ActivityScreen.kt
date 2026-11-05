package com.miku.mcp.ui.activity

import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.History
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.miku.mcp.activity.ActivityEntry
import com.miku.mcp.activity.ActivityLog
import com.miku.mcp.activity.ActivityStatus
import com.miku.mcp.ui.common.EmptyState
import com.miku.mcp.ui.common.copyToClipboard
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

// Which statuses the list shows
private enum class Filter(val label: String, val matches: (ActivityStatus) -> Boolean) {
    ALL("All", { true }),
    SUCCESS("Success", { it == ActivityStatus.SUCCESS }),
    ERRORS("Errors", { it == ActivityStatus.ERROR }),
    BLOCKED("Blocked", { it == ActivityStatus.BLOCKED })
}

private val timeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivityScreen() {
    val context = LocalContext.current
    val log = remember { ActivityLog.get(context) }
    val entries by log.entries.collectAsState()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val zone = remember { ZoneId.systemDefault() }

    var filter by rememberSaveable { mutableStateOf(Filter.ALL) }
    var selectedId by remember { mutableStateOf<Long?>(null) }   // id, so the open sheet follows live updates
    var confirmClear by remember { mutableStateOf(false) }

    val visible = entries.filter { filter.matches(it.status) }
    // Group by calendar day, keeping the newest-first order
    val byDay = visible.groupBy { Instant.ofEpochMilli(it.startedAt).atZone(zone).toLocalDate() }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text("Activity") },
                actions = {
                    IconButton(enabled = entries.isNotEmpty(), onClick = { confirmClear = true }) {
                        Icon(Icons.Rounded.DeleteSweep, contentDescription = "Clear history")
                    }
                },
                scrollBehavior = scrollBehavior
            )
        }
    ) { padding ->
        if (entries.isEmpty()) {
            EmptyState(
                icon = Icons.Rounded.History,
                title = "No activity yet",
                message = "Every tool Claude runs on your phone will appear here.",
                modifier = Modifier.padding(padding)
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp, end = 16.dp,
                    top = padding.calculateTopPadding() + 8.dp,
                    bottom = padding.calculateBottomPadding() + 24.dp
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    // Filter chips scroll sideways on narrow screens
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Filter.entries.forEach { option ->
                            FilterChip(
                                selected = filter == option,
                                onClick = { filter = option },
                                label = { Text(option.label) }
                            )
                        }
                    }
                }

                if (visible.isEmpty()) {
                    item {
                        Text(
                            "Nothing matches this filter.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                }

                byDay.forEach { (date, dayEntries) ->
                    item(key = "day-$date") {
                        Text(
                            dayLabel(date),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 8.dp, top = 8.dp)
                        )
                    }
                    items(dayEntries, key = { it.id }) { entry ->
                        ActivityRow(entry, zone, onClick = { selectedId = entry.id })
                    }
                }
            }
        }
    }

    // Details of the tapped row, in a bottom sheet
    val selected = entries.firstOrNull { it.id == selectedId }
    if (selected != null) {
        DetailSheet(selected, zone, onDismiss = { selectedId = null })
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear the history?") },
            text = { Text("All recorded actions will be removed from this phone. Your files are not affected.") },
            confirmButton = {
                TextButton(onClick = {
                    log.clear()
                    confirmClear = false
                }) { Text("Clear") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } }
        )
    }
}

// One action: status badge, tool name, arguments, time and duration
@Composable
private fun ActivityRow(entry: ActivityEntry, zone: ZoneId, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        ListItem(
            leadingContent = { StatusBadge(entry.status) },
            headlineContent = { Text(entry.title) },
            supportingContent = {
                Text(
                    entry.summary,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            },
            trailingContent = {
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        Instant.ofEpochMilli(entry.startedAt).atZone(zone).format(timeFormat),
                        style = MaterialTheme.typography.labelMedium
                    )
                    if (entry.status != ActivityStatus.RUNNING) {
                        Text(
                            formatDuration(entry.durationMs),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
        )
    }
}

// Round colored badge: spinner while running, otherwise an icon that matches the outcome
@Composable
private fun StatusBadge(status: ActivityStatus) {
    val colors = MaterialTheme.colorScheme
    val (container, content, icon) = when (status) {
        ActivityStatus.RUNNING -> Triple(colors.secondaryContainer, colors.onSecondaryContainer, null)
        ActivityStatus.SUCCESS -> Triple(colors.primaryContainer, colors.onPrimaryContainer, Icons.Rounded.Check)
        ActivityStatus.ERROR -> Triple(colors.errorContainer, colors.onErrorContainer, Icons.Rounded.ErrorOutline)
        ActivityStatus.BLOCKED -> Triple(colors.tertiaryContainer, colors.onTertiaryContainer, Icons.Rounded.Block)
    }
    Surface(shape = CircleShape, color = container, contentColor = content, modifier = Modifier.size(40.dp)) {
        Box(contentAlignment = Alignment.Center) {
            if (icon == null) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                Icon(icon as ImageVector, contentDescription = status.name)
            }
        }
    }
}

// Bottom sheet with everything recorded about one action
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailSheet(entry: ActivityEntry, zone: ZoneId, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val whenText = Instant.ofEpochMilli(entry.startedAt).atZone(zone)
        .format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM))
    val statusText = entry.status.name.lowercase().replaceFirstChar { it.uppercase() }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                StatusBadge(entry.status)
                Column {
                    Text(entry.title, style = MaterialTheme.typography.titleLarge)
                    Text(
                        "${entry.tool}  ·  $statusText",
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            DetailField("Time", whenText + if (entry.status == ActivityStatus.RUNNING) "" else "  ·  ${formatDuration(entry.durationMs)}")
            DetailField("Arguments", entry.summary, mono = true)
            DetailField(
                if (entry.status == ActivityStatus.ERROR || entry.status == ActivityStatus.BLOCKED) "Reason" else "Result",
                entry.output.ifEmpty { if (entry.status == ActivityStatus.RUNNING) "Still running..." else "(empty)" },
                mono = true
            )

            OutlinedButton(onClick = {
                copyToClipboard(
                    context, "Activity",
                    "${entry.title} (${entry.tool})\n$statusText\n$whenText\n${entry.summary}\n${entry.output}"
                )
            }) {
                Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("  Copy details")
            }
        }
    }
}

// Small label above a value
@Composable
private fun DetailField(label: String, value: String, mono: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default
        )
    }
}

private fun dayLabel(date: LocalDate): String {
    val today = LocalDate.now()
    return when (date) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
    }
}

private fun formatDuration(ms: Long): String =
    if (ms < 1000) "$ms ms" else "%.1f s".format(ms / 1000.0)