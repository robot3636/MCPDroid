package com.miku.mcp.tools

import kotlinx.serialization.json.JsonObject

// What a tool returns: plain text plus an error flag
data class ToolResult(val text: String, val isError: Boolean = false)

// Every tool implements this; adding a new feature = adding a new class
interface McpTool {
    val name: String               // unique id Claude uses to call the tool
    val description: String        // tells Claude when to use it
    val inputSchema: JsonObject    // JSON Schema describing the arguments

    // Metadata Claude uses to label tools and decide which ones need the user's approval
    val title: String get() = name            // human-readable name
    val readOnly: Boolean get() = false       // true = never changes anything
    val destructive: Boolean get() = true     // true = may overwrite or delete (only matters when not read-only)
    val idempotent: Boolean get() = false     // true = repeating the call has no extra effect
    val openWorld: Boolean get() = false      // true = reaches outside the phone (the internet)
    // False for tools whose output may be private content: it is then left out of the activity history
    val recordsOutput: Boolean get() = true
    suspend fun execute(args: JsonObject): ToolResult
}

// A tool together with the module (group) it belongs to
data class RegisteredTool(val tool: McpTool, val module: String)

// Holds every tool and hides the ones the user switched off
class ToolRegistry(
    // Asked on every list/call, so toggles in the app take effect immediately
    private val isEnabled: (module: String, toolName: String) -> Boolean = { _, _ -> true }
) {
    private val entries = linkedMapOf<String, RegisteredTool>()

    fun register(tool: McpTool, module: String) {
        entries[tool.name] = RegisteredTool(tool, module)
    }

    // Everything registered, enabled or not (the app's Tools screen shows all of it)
    fun allRegistered(): List<RegisteredTool> = entries.values.toList()

    // Only the tools the user switched on: this is what Claude sees
    fun all(): List<McpTool> =
        entries.values.filter { isEnabled(it.module, it.tool.name) }.map { it.tool }

    // Returns null for unknown tools AND for tools that are switched off
    fun get(name: String): McpTool? =
        entries[name]?.takeIf { isEnabled(it.module, name) }?.tool

    // True when the tool exists but the user switched it off
    fun isDisabled(name: String): Boolean {
        val entry = entries[name] ?: return false
        return !isEnabled(entry.module, name)
    }
}