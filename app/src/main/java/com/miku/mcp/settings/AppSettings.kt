package com.miku.mcp.settings

import android.content.Context

// Plain (non-secret) app preferences; secrets stay in SecretStore
class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)

    // Local port the Ktor server listens on
    var port: Int
        get() = prefs.getInt(KEY_PORT, DEFAULT_PORT)
        set(value) = prefs.edit().putInt(KEY_PORT, value).apply()

    // Whether the tunnel starts by itself right after the server
    var tunnelAutoStart: Boolean
        get() = prefs.getBoolean(KEY_TUNNEL_AUTO_START, true)
        set(value) = prefs.edit().putBoolean(KEY_TUNNEL_AUTO_START, value).apply()

    companion object {
        const val DEFAULT_PORT = 8765
        private const val KEY_PORT = "port"
        private const val KEY_TUNNEL_AUTO_START = "tunnel_auto_start"
    }
}