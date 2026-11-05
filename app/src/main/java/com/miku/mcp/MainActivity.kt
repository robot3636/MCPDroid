package com.miku.mcp

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.miku.mcp.settings.ThemeMode
import com.miku.mcp.settings.UiPreferences
import com.miku.mcp.ui.navigation.AppRoot
import com.miku.mcp.ui.theme.MyComposeApplicationTheme

// The Activity is only a host: the server and tunnel live in the foreground service
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = UiPreferences.get(this)

        setContent {
            // Re-read whenever the user changes the appearance in Settings
            val ui by prefs.state.collectAsState()
            val dark = when (ui.themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }

            // The app theme can differ from the system theme, so the status and navigation bar
            // icons must follow the app theme, not the system one
            DisposableEffect(dark) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                    navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark }
                )
                onDispose { }
            }

            MyComposeApplicationTheme(darkTheme = dark, dynamicColor = ui.dynamicColor) {
                AppRoot()   // bottom navigation + all screens
            }
        }
    }
}