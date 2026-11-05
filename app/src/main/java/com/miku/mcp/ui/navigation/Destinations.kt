package com.miku.mcp.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Handyman
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.Handyman
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.ui.graphics.vector.ImageVector

// Screens reached from inside another screen (the bottom bar is hidden on them)
// Screens reached from inside another screen (the bottom bar is hidden on them)
object Routes {
    const val TUNNEL_SETTINGS = "tunnel_settings"
    const val LOGS = "logs"
    const val MODULE = "module/{id}" // detail screen of one tool module
    const val LICENSES = "licenses"
    fun module(id: String) = "module/$id"
}
// The four sections shown in the bottom navigation bar
enum class TopDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,           // shown when not selected (outlined)
    val selectedIcon: ImageVector    // shown when selected (filled)
) {
    Dashboard("dashboard", "Dashboard", Icons.Outlined.Dashboard, Icons.Rounded.Dashboard),
    Tools("tools", "Tools", Icons.Outlined.Handyman, Icons.Rounded.Handyman),
    Activity("activity", "Activity", Icons.Outlined.History, Icons.Rounded.History),
    Settings("settings", "Settings", Icons.Outlined.Settings, Icons.Rounded.Settings)
}