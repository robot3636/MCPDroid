package com.miku.mcp.tools

import android.content.Context
import com.miku.mcp.tools.termux.TermuxRunCommandTool

object ModuleIds {
    const val FILES = "files"
    const val DOWNLOADS = "downloads"
    const val TERMUX = "termux"
}

// What the Tools screen shows for each module
data class ModuleInfo(val id: String, val title: String, val description: String)

val ToolModules = listOf(
    ModuleInfo(ModuleIds.FILES, "File management", "Browse, read, create, edit and delete files in your workspaces"),
    ModuleInfo(ModuleIds.DOWNLOADS, "Downloads", "Download files from the internet into a workspace"),
    ModuleInfo(ModuleIds.TERMUX, "Termux", "Run shell commands in Termux. Off by default and needs a quick setup")
)

// Builds the tool registry once per process, so the UI and the service share the same instance
object ToolHub {
    @Volatile private var cached: ToolRegistry? = null

    fun registry(context: Context): ToolRegistry =
        cached ?: synchronized(this) {
            cached ?: build(context.applicationContext).also { cached = it }
        }

    // Text describing the workspaces, sent to Claude in the handshake
    fun workspaceSummary(context: Context): String =
        WorkspaceFs(ToolSettings.get(context)).describe()

    private fun build(app: Context): ToolRegistry {
        val settings = ToolSettings.get(app)
        // Toggles are checked on every call, so switching a tool off applies immediately
        val reg = ToolRegistry { module, tool -> settings.isEnabled(module, tool) }
        reg.registerFileTools(WorkspaceFs(settings), settings)
        reg.register(TermuxRunCommandTool(app, settings), ModuleIds.TERMUX)   // stays off until the user sets it up
        return reg
    }
}