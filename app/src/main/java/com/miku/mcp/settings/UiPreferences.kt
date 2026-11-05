package com.miku.mcp.settings

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

// How the app picks light or dark colors
enum class ThemeMode(val label: String) {
    SYSTEM("System"),
    LIGHT("Light"),
    DARK("Dark")
}

// Appearance choices the whole UI reacts to
data class UiState(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true   // wallpaper-based colors on Android 12+
)

// Persistent appearance settings; the Activity observes `state`, so changes apply instantly
class UiPreferences private constructor(context: Context) {

    private val prefs = context.getSharedPreferences("ui_prefs", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(
        UiState(
            // An unknown saved value falls back to "follow the system"
            themeMode = runCatching { ThemeMode.valueOf(prefs.getString(KEY_THEME, null) ?: "SYSTEM") }
                .getOrDefault(ThemeMode.SYSTEM),
            dynamicColor = prefs.getBoolean(KEY_DYNAMIC, true)
        )
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    fun setThemeMode(mode: ThemeMode) {
        _state.update { it.copy(themeMode = mode) }
        prefs.edit().putString(KEY_THEME, mode.name).apply()
    }

    fun setDynamicColor(enabled: Boolean) {
        _state.update { it.copy(dynamicColor = enabled) }
        prefs.edit().putBoolean(KEY_DYNAMIC, enabled).apply()
    }

    companion object {
        private const val KEY_THEME = "theme_mode"
        private const val KEY_DYNAMIC = "dynamic_color"

        @Volatile private var instance: UiPreferences? = null

        // One instance per process
        fun get(context: Context): UiPreferences =
            instance ?: synchronized(this) {
                instance ?: UiPreferences(context.applicationContext).also { instance = it }
            }
    }
}