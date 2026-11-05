package com.miku.mcp.server

import android.content.Context
import com.miku.mcp.mcp.McpJson
import kotlinx.serialization.Serializable
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

// A client (e.g. Claude) that registered itself through Dynamic Client Registration
@Serializable
data class RegisteredClient(
    val clientId: String,
    val name: String,
    val redirectUris: List<String>,
    val createdAt: Long
)

// Only a SHA-256 hash of each token is stored, so leaked preferences do not reveal usable tokens
@Serializable
private data class StoredToken(val hash: String, val clientId: String, val expiresAt: Long)

// Everything that survives an app restart
@Serializable
private data class PersistedState(
    val clients: List<RegisteredClient> = emptyList(),
    val access: List<StoredToken> = emptyList(),
    val refresh: List<StoredToken> = emptyList()
)

// A one-time authorization code waiting to be exchanged at /token (memory only)
data class AuthCode(
    val clientId: String,
    val redirectUri: String,
    val codeChallenge: String,
    val expiresAt: Long
)

data class TokenPair(val accessToken: String, val refreshToken: String, val expiresInSeconds: Long)

// Single source of truth for OAuth state. One instance per process (service and UI share it).
class OAuthStore private constructor(context: Context) {

    private val prefs = context.getSharedPreferences("mcp_oauth", Context.MODE_PRIVATE)
    private val random = SecureRandom()
    private val codes = ConcurrentHashMap<String, AuthCode>()
    private var state: PersistedState = loadState()

    // Brute-force protection for the consent page
    private var failures = 0
    private var lockedUntil = 0L

    // ---------- Clients ----------

    @Synchronized
    fun registerClient(name: String, redirectUris: List<String>): RegisteredClient {
        val client = RegisteredClient(randomHex(16), name, redirectUris, now())
        // Keep only the newest clients so a flood of registrations cannot grow storage forever
        state = state.copy(clients = (state.clients + client).takeLast(MAX_CLIENTS))
        save()
        return client
    }

    @Synchronized
    fun findClient(clientId: String): RegisteredClient? =
        state.clients.firstOrNull { it.clientId == clientId }

    // ---------- Authorization codes ----------

    fun createCode(clientId: String, redirectUri: String, codeChallenge: String): String {
        val t = now()
        codes.entries.removeIf { it.value.expiresAt < t }   // drop expired codes
        val code = randomHex(32)
        codes[code] = AuthCode(clientId, redirectUri, codeChallenge, t + CODE_TTL_MS)
        return code
    }

    // Removes the code on first use, so it can never be exchanged twice
    fun consumeCode(code: String): AuthCode? {
        val entry = codes.remove(code) ?: return null
        return if (entry.expiresAt >= now()) entry else null
    }

    // ---------- Tokens ----------

    @Synchronized
    fun issueTokens(clientId: String): TokenPair {
        val access = randomHex(32)
        val refresh = randomHex(32)
        val t = now()
        val current = purged(state)
        state = current.copy(
            access = (current.access + StoredToken(sha256(access), clientId, t + ACCESS_TTL_MS)).takeLast(MAX_TOKENS),
            refresh = (current.refresh + StoredToken(sha256(refresh), clientId, t + REFRESH_TTL_MS)).takeLast(MAX_TOKENS)
        )
        save()
        return TokenPair(access, refresh, ACCESS_TTL_MS / 1000)
    }

    @Synchronized
    fun isAccessTokenValid(token: String): Boolean {
        val hash = sha256(token)
        return state.access.any { it.hash == hash && it.expiresAt > now() }
    }

    // Refresh-token rotation: the old token is revoked the moment it is used
    @Synchronized
    fun refreshTokens(refreshToken: String, clientId: String): TokenPair? {
        val hash = sha256(refreshToken)
        val entry = state.refresh.firstOrNull {
            it.hash == hash && it.clientId == clientId && it.expiresAt > now()
        } ?: return null
        state = state.copy(refresh = state.refresh - entry)
        return issueTokens(clientId)
    }

    // Forgets every client and token (used when the owner token is regenerated)
    @Synchronized
    fun revokeAll() {
        state = PersistedState()
        codes.clear()
        save()
    }

    // ---------- Consent page rate limiting ----------

    @Synchronized fun isLockedOut(): Boolean = now() < lockedUntil

    @Synchronized
    fun recordFailure() {
        failures++
        if (failures >= MAX_FAILURES) {
            lockedUntil = now() + LOCKOUT_MS
            failures = 0
        }
    }

    @Synchronized fun recordSuccess() { failures = 0 }

    // ---------- Internals ----------

    private fun loadState(): PersistedState = try {
        prefs.getString(KEY, null)?.let { McpJson.decodeFromString<PersistedState>(it) } ?: PersistedState()
    } catch (e: Exception) {
        PersistedState()   // corrupted data: start clean
    }

    private fun save() {
        prefs.edit().putString(KEY, McpJson.encodeToString(state)).apply()
    }

    private fun purged(s: PersistedState): PersistedState {
        val t = now()
        return s.copy(
            access = s.access.filter { it.expiresAt > t },
            refresh = s.refresh.filter { it.expiresAt > t }
        )
    }

    private fun randomHex(bytes: Int): String =
        ByteArray(bytes).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private fun now() = System.currentTimeMillis()

    companion object {
        private const val KEY = "state"
        private const val ACCESS_TTL_MS = 60L * 60 * 1000                 // access token: 1 hour
        private const val REFRESH_TTL_MS = 30L * 24 * 60 * 60 * 1000      // refresh token: 30 days
        private const val CODE_TTL_MS = 5L * 60 * 1000                    // authorization code: 5 minutes
        private const val MAX_CLIENTS = 50
        private const val MAX_TOKENS = 200
        private const val MAX_FAILURES = 5
        private const val LOCKOUT_MS = 60_000L

        // Redirect URIs a registering client may use: Claude's hosted callbacks only
        private val HOSTED_CALLBACKS = setOf(
            "https://claude.ai/api/mcp/auth_callback",
            "https://claude.com/api/mcp/auth_callback"
        )

        // Hosted callbacks, or a loopback /callback on any port (used by Claude Code)
        fun isAllowedRedirectUri(uri: String): Boolean {
            val parsed = try { URI(uri) } catch (e: Exception) { return false }
            if (parsed.fragment != null) return false
            if (uri in HOSTED_CALLBACKS) return true
            return parsed.scheme == "http" &&
                (parsed.host == "localhost" || parsed.host == "127.0.0.1") &&
                parsed.path == "/callback"
        }

        @Volatile private var instance: OAuthStore? = null

        fun get(context: Context): OAuthStore =
            instance ?: synchronized(this) {
                instance ?: OAuthStore(context.applicationContext).also { instance = it }
            }
    }
}