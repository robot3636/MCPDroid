package com.miku.mcp.ui.common

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector

// Temporary screen for sections that arrive in the next steps
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaceholderScreen(title: String, message: String, icon: ImageVector) {
    Scaffold(topBar = { LargeTopAppBar(title = { Text(title) }) }) { padding ->
        EmptyState(icon = icon, title = "Coming next", message = message, modifier = Modifier.padding(padding))
    }
}