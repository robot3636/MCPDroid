package com.miku.mcp.ui.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.miku.mcp.ui.common.PlaceholderScreen
import com.miku.mcp.ui.dashboard.DashboardScreen
import com.miku.mcp.ui.logs.LogsScreen
import com.miku.mcp.ui.tools.ModuleDetailScreen
import com.miku.mcp.ui.tools.ToolsScreen
import com.miku.mcp.ui.tunnel.TunnelSettingsScreen
import com.miku.mcp.ui.activity.ActivityScreen
import com.miku.mcp.ui.settings.LicensesScreen
import com.miku.mcp.ui.settings.SettingsScreen
import com.miku.mcp.tools.ModuleIds
import com.miku.mcp.ui.tools.TermuxToolScreen

// App shell: bottom navigation for the four sections plus the navigation graph
@Composable
fun AppRoot() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    // The bar is only shown on the four top-level screens
    val showBottomBar = TopDestination.entries.any { it.route == currentRoute }

    Scaffold(
        // Each screen below has its own Scaffold and handles the status bar itself
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            AnimatedVisibility(
                visible = showBottomBar,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut()
            ) {
                NavigationBar {
                    TopDestination.entries.forEach { destination ->
                        val selected = currentRoute == destination.route
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                navController.navigate(destination.route) {
                                    // One back stack per tab, restoring each tab's state
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = {
                                Icon(
                                    imageVector = if (selected) destination.selectedIcon else destination.icon,
                                    contentDescription = null
                                )
                            },
                            label = { Text(destination.label) }
                        )
                    }
                }
            }
        }
    ) { padding ->
        // Only the bottom bar's height is applied here; screens handle the top inset themselves
        val bottom = padding.calculateBottomPadding()

        NavHost(
            navController = navController,
            startDestination = TopDestination.Dashboard.route,
            modifier = Modifier
                .padding(bottom = bottom)
                .consumeWindowInsets(PaddingValues(bottom = bottom)),   // avoids counting the nav bar twice
            // Short fade plus a small horizontal shift, in the spirit of the Material shared-axis motion
            enterTransition = { fadeIn(tween(250)) + slideInHorizontally(tween(250)) { it / 12 } },
            exitTransition = { fadeOut(tween(150)) },
            popEnterTransition = { fadeIn(tween(250)) + slideInHorizontally(tween(250)) { -it / 12 } },
            popExitTransition = { fadeOut(tween(150)) + slideOutHorizontally(tween(250)) { it / 12 } }
        ) {
            composable(TopDestination.Dashboard.route) {
                DashboardScreen(
                    onOpenTunnelSettings = { navController.navigate(Routes.TUNNEL_SETTINGS) },
                    onOpenLogs = { navController.navigate(Routes.LOGS) }
                )
            }
            composable(TopDestination.Tools.route) {
                ToolsScreen(onOpenModule = { id -> navController.navigate(Routes.module(id)) })
            }
            composable(TopDestination.Activity.route) { ActivityScreen() }
            composable(TopDestination.Settings.route) {
                SettingsScreen(
                    onOpenTunnelSettings = { navController.navigate(Routes.TUNNEL_SETTINGS) },
                    onOpenLogs = { navController.navigate(Routes.LOGS) },
                    onOpenLicenses = { navController.navigate(Routes.LICENSES) }
                )
            }
            composable(Routes.TUNNEL_SETTINGS) { TunnelSettingsScreen(onBack = { navController.popBackStack() }) }
            composable(Routes.LOGS) { LogsScreen(onBack = { navController.popBackStack() }) }
            composable(Routes.LICENSES) { LicensesScreen(onBack = { navController.popBackStack() }) }
            
            // Detail screen of one tool module; the module id travels in the route
            composable(
                route = Routes.MODULE,
                arguments = listOf(navArgument("id") { type = NavType.StringType })
            ) { entry ->
                val id = entry.arguments?.getString("id").orEmpty()
                if (id == ModuleIds.TERMUX) {
                    TermuxToolScreen(onBack = { navController.popBackStack() })   // guided setup
                } else {
                    ModuleDetailScreen(moduleId = id, onBack = { navController.popBackStack() })
                }
            }
        }
    }
}