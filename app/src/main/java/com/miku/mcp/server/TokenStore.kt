package com.miku.mcp.server

import android.content.Context
import java.security.SecureRandom

// Generates the access token once and keeps it in private app storage
class TokenStore(context: Context) {
    private val prefs = context.getSharedPreferences("mcp_security", Context.MODE_PRIVATE)

    fun getOrCreate(): String =
        prefs.getString(KEY, null) ?: generate().also { prefs.edit().putString(KEY, it).apply() }

    // Replaces the token; the old one stops working immediately after a server restart
    fun rotate(): String = generate().also { prefs.edit().putString(KEY, it).apply() }

    // 32 random bytes encoded as 64 hex characters
    private fun generate(): String {
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private companion object { const val KEY = "bearer_token" }
}