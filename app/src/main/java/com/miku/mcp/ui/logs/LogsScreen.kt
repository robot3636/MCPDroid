package com.miku.mcp.ui.logs

import android.os.Build
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.miku.mcp.service.LogBuffer
import com.miku.mcp.ui.common.EmptyState
import com.miku.mcp.ui.common.copyToClipboard
import kotlinx.coroutines.launch

// Live output of the cloudflared process and of the server requests
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val lines by LogBuffer.lines.collectAsState()
    val listState = rememberLazyListState()
    val colors = MaterialTheme.colorScheme

    // Follow new lines only while the user is already near the bottom,
    // so scrolling up to read an old line is not interrupted
    val followTail by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            lastVisible >= info.totalItemsCount - 2
        }
    }
    LaunchedEffect(lines.size) {
        if (followTail && lines.isNotEmpty()) listState.scrollToItem(lines.lastIndex)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Logs") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(
                        enabled = lines.isNotEmpty(),
                        onClick = {
                            copyToClipboard(context, "Logs", lines.joinToString("\n"))
                            if (Build.VERSION.SDK_INT < 33) scope.launch { snackbar.showSnackbar("Logs copied") }
                        }
                    ) { Icon(Icons.Rounded.ContentCopy, contentDescription = "Copy logs") }
                    IconButton(enabled = lines.isNotEmpty(), onClick = { LogBuffer.clear() }) {
                        Icon(Icons.Rounded.DeleteSweep, contentDescription = "Clear logs")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        if (lines.isEmpty()) {
            EmptyState(
                icon = Icons.Rounded.Terminal,
                title = "No output yet",
                message = "Logs appear here when the server or the tunnel runs.",
                modifier = Modifier.padding(padding)
            )
        } else {
            Surface(
                modifier = Modifier.fillMaxSize().padding(padding),
                color = colors.surfaceContainerLowest
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    itemsIndexed(lines) { _, line ->
                        // cloudflared marks levels with INF / WRN / ERR
                        val color = when {
                            " ERR " in line -> colors.error
                            " WRN " in line -> colors.tertiary
                            line.startsWith("---") -> colors.primary
                            else -> colors.onSurface
                        }
                        Text(
                            text = line,
                            color = color,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
    }
}