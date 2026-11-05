package com.miku.mcp.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp

// One third-party component the app uses
private data class LicenseInfo(
    val name: String,
    val holder: String,
    val license: String,
    val projectUrl: String,
    val licenseUrl: String
)

private const val APACHE_URL = "https://www.apache.org/licenses/LICENSE-2.0"

private val licenses = listOf(
    LicenseInfo("Kotlin, kotlinx.coroutines and kotlinx.serialization", "JetBrains s.r.o. and contributors", "Apache License 2.0", "https://kotlinlang.org", APACHE_URL),
    LicenseInfo("Ktor", "JetBrains s.r.o. and contributors", "Apache License 2.0", "https://ktor.io", APACHE_URL),
    LicenseInfo("Jetpack Compose, Material 3, Navigation, Lifecycle, Activity and Core", "The Android Open Source Project", "Apache License 2.0", "https://developer.android.com/jetpack", APACHE_URL),
    LicenseInfo("cloudflared", "Cloudflare, Inc.", "Apache License 2.0", "https://github.com/cloudflare/cloudflared", APACHE_URL)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicensesScreen(onBack: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    // Opens a link in the browser; a phone without a browser must not crash the app
    val open: (String) -> Unit = { url -> runCatching { uriHandler.openUri(url) } }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            MediumTopAppBar(
                title = { Text("Open-source licenses") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                scrollBehavior = scrollBehavior
            )
        }
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
                    "AI MCP is built with these open-source projects. Thank you to their authors.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
            }
            items(licenses, key = { it.name }) { info ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.extraLarge,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
                ) {
                    Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(info.name, style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Copyright ${info.holder}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(info.license, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        Row {
                            TextButton(onClick = { open(info.projectUrl) }) { Text("Project page") }
                            TextButton(onClick = { open(info.licenseUrl) }) { Text("License text") }
                        }
                    }
                }
            }
        }
    }
}