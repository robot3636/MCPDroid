package com.miku.mcp.tools

import android.content.Context
import com.miku.mcp.mcp.McpJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import java.io.File
import java.util.UUID

// A folder Claude may work in. The alias is the first segment of every path Claude uses.
@Serializable
data class Workspace(val id: String, val alias: String, val path: String, val writable: Boolean)

// Everything the user can configure about the tools
@Serializable
data class ToolConfig(
    val disabledModules: Set<String> = emptySet(),   // normal modules switched off (default: all on)
    val enabledOptIn: Set<String> = emptySet(),      // opt-in modules switched on (default: all off)
    val disabledTools: Set<String> = emptySet(),     // single tools switched off
    val workspaces: List<Workspace> = emptyList(),
    val maxDownloadMb: Int = 100,
    val seeded: Boolean = false,                     // true once the default workspace exists
    val termuxPackage: String = "com.termux",        // which Termux build the Termux tool talks to
    val termuxVerified: Boolean = false              // true after a successful "Test connection"
)

// Modules that stay OFF until the user turns them on, because they are powerful
val OPT_IN_MODULES = setOf(ModuleIds.TERMUX)

// True when the module is on, taking the opt-in rule into account
fun ToolConfig.isModuleEnabled(module: String): Boolean =
    if (module in OPT_IN_MODULES) module in enabledOptIn else module !in disabledModules

// Persistent tool configuration. The UI observes `state`; the tools read it on every call.
class ToolSettings private constructor(context: Context) {

    private val prefs = context.getSharedPreferences("tool_settings", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(load(context))
    val state: StateFlow<ToolConfig> = _state.asStateFlow()

    // ---------- Switches ----------

    fun isEnabled(module: String, tool: String): Boolean {
        val config = _state.value
        return config.isModuleEnabled(module) && tool !in config.disabledTools
    }

    fun setModuleEnabled(module: String, enabled: Boolean) = update {
        if (module in OPT_IN_MODULES) {
            it.copy(enabledOptIn = if (enabled) it.enabledOptIn + module else it.enabledOptIn - module)
        } else {
            it.copy(disabledModules = if (enabled) it.disabledModules - module else it.disabledModules + module)
        }
    }

    fun setToolEnabled(tool: String, enabled: Boolean) = update {
        it.copy(disabledTools = if (enabled) it.disabledTools - tool else it.disabledTools + tool)
    }

    fun setMaxDownloadMb(mb: Int) = update { it.copy(maxDownloadMb = mb) }

    // ---------- Termux ----------

    // A different package means a different app, so the previous test result no longer applies
    fun setTermuxPackage(packageName: String) = update {
        it.copy(termuxPackage = packageName.trim(), termuxVerified = false)
    }

    fun setTermuxVerified(verified: Boolean) = update { it.copy(termuxVerified = verified) }

    // ---------- Workspaces ----------

    fun setWorkspaceWritable(id: String, writable: Boolean) = update { config ->
        config.copy(workspaces = config.workspaces.map { if (it.id == id) it.copy(writable = writable) else it })
    }

    fun removeWorkspace(id: String) = update { config ->
        config.copy(workspaces = config.workspaces.filterNot { it.id == id })
    }

    // Adds a folder as a READ-ONLY workspace. Returns an error message, or null on success.
    fun addWorkspace(path: String, preferredAlias: String? = null): String? {
        val dir = try { File(path).canonicalFile } catch (e: Exception) { return "Invalid folder path" }
        if (!dir.isDirectory) return "That folder does not exist"

        var error: String? = null
        update { config ->
            if (config.workspaces.any { it.path == dir.path }) {
                error = "This folder is already a workspace"
                config
            } else {
                val alias = uniqueAlias(sanitizeAlias(preferredAlias ?: dir.name), config.workspaces)
                // New folders start read-only: the user must explicitly allow writing
                config.copy(workspaces = config.workspaces + Workspace(newId(), alias, dir.path, writable = false))
            }
        }
        return error
    }

    // Renames a workspace. Returns an error message, or null on success.
    fun renameWorkspace(id: String, newAlias: String): String? {
        val alias = newAlias.trim()
        if (!ALIAS_REGEX.matches(alias)) return "Use 1-32 letters, digits, dots, dashes or underscores"

        var error: String? = null
        update { config ->
            if (config.workspaces.any { it.id != id && it.alias.equals(alias, ignoreCase = true) }) {
                error = "That name is already used"
                config
            } else {
                config.copy(workspaces = config.workspaces.map { if (it.id == id) it.copy(alias = alias) else it })
            }
        }
        return error
    }

    // ---------- Internals ----------

    // Applies a change, publishes it to the UI, and saves it (only when something really changed)
    @Synchronized
    private fun update(transform: (ToolConfig) -> ToolConfig) {
        val next = transform(_state.value)
        if (next != _state.value) {
            _state.value = next
            save(next)
        }
    }

    private fun save(config: ToolConfig) {
        prefs.edit().putString(KEY, McpJson.encodeToString(config)).apply()
    }

    // Loads the saved config and creates the default "app" workspace on the very first run
    private fun load(context: Context): ToolConfig {
        val parsed = try {
            prefs.getString(KEY, null)?.let { McpJson.decodeFromString<ToolConfig>(it) }
        } catch (e: Exception) {
            null   // corrupted data: start clean
        }
        val stored = parsed ?: ToolConfig()
        if (stored.seeded) return stored

        // Same folder the file tools used before workspaces existed, so old files stay reachable
        val appDir = (context.getExternalFilesDir("workspace") ?: File(context.filesDir, "workspace"))
            .apply { mkdirs() }
        val seeded = stored.copy(
            workspaces = listOf(Workspace(newId(), "app", appDir.canonicalPath, writable = true)) + stored.workspaces,
            seeded = true
        )
        save(seeded)
        return seeded
    }

    companion object {
        private const val KEY = "config"
        private val ALIAS_REGEX = Regex("[A-Za-z0-9._-]{1,32}")

        private fun newId() = UUID.randomUUID().toString().take(8)

        // Turns a folder name into a valid alias
        private fun sanitizeAlias(raw: String): String =
            raw.replace(Regex("[^A-Za-z0-9._-]"), "-").trim('-', '.').take(32).ifEmpty { "workspace" }

        // Adds -2, -3... until the alias is unused
        private fun uniqueAlias(base: String, existing: List<Workspace>): String {
            var candidate = base
            var n = 2
            while (existing.any { it.alias.equals(candidate, ignoreCase = true) }) {
                candidate = "${base.take(28)}-$n"
                n++
            }
            return candidate
        }

        @Volatile private var instance: ToolSettings? = null

        // One instance per process, shared by the service and the UI
        fun get(context: Context): ToolSettings =
            instance ?: synchronized(this) {
                instance ?: ToolSettings(context.applicationContext).also { instance = it }
            }
    }
}