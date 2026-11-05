package com.miku.mcp.mcp

import com.miku.mcp.activity.ActivityLog
import com.miku.mcp.tools.ToolRegistry
import com.miku.mcp.tools.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

// Routes JSON-RPC methods to the right logic (protocol layer, no networking here).
// "activity" records every tool call; "instructions" supplies extra text for Claude
// (the current workspaces), evaluated at each handshake.
class McpHandler(
    private val registry: ToolRegistry,
    private val activity: ActivityLog? = null,
    private val instructions: () -> String = { "" }
) {

    companion object {
        // Protocol versions this server speaks, newest first
        val SUPPORTED_VERSIONS = listOf("2025-06-18", "2025-03-26", "2024-11-05")

        // Shown to Claude at connection time so it knows how to use the tools well
        private const val INSTRUCTIONS =
            "This server gives access to folders (workspaces) on the user's Android phone. " +
            "Call list_files with path '.' to see the workspaces; every other path starts with a workspace name. " +
            "Some workspaces are read-only. read_file returns UTF-8 text in chunks, write_file creates, " +
            "overwrites or appends, and download_file saves a file from an http(s) URL into a writable workspace."
    }

    // Returns null when no reply must be sent (notifications)
    suspend fun handle(req: JsonRpcRequest): JsonRpcResponse? {
        // Notifications (no id), e.g. "notifications/initialized", never get a reply
        if (req.id == null) return null

        return try {
            when (req.method) {
                "initialize" -> ok(req.id, initializeResult(req.params))
                "ping" -> ok(req.id, buildJsonObject {})
                "tools/list" -> ok(req.id, listTools())
                "tools/call" -> ok(req.id, callTool(req.params))
                else -> fail(req.id, -32601, "Method not found: ${req.method}")
            }
        } catch (e: IllegalArgumentException) {
            fail(req.id, -32602, e.message ?: "Invalid params")   // bad input from client
        } catch (e: Exception) {
            fail(req.id, -32603, "Internal error: ${e.message}")  // unexpected failure
        }
    }

    // Handshake: version negotiation plus what we support
    private fun initializeResult(params: JsonObject?): JsonObject = buildJsonObject {
        // Use the client's version when we support it, otherwise answer with our newest one
        val requested = params?.get("protocolVersion")?.jsonPrimitive?.contentOrNull
        put("protocolVersion", if (requested != null && requested in SUPPORTED_VERSIONS) requested else SUPPORTED_VERSIONS.first())
        putJsonObject("capabilities") {
            putJsonObject("tools") { put("listChanged", false) }
        }
        putJsonObject("serverInfo") {
            put("name", "AI MCP")
            put("title", "AI MCP (Android)")
            put("version", "0.1.0")
        }
        put("instructions", "$INSTRUCTIONS ${instructions()}")
    }

    // Describe every enabled tool so Claude knows what it can call
    private fun listTools(): JsonObject = buildJsonObject {
        putJsonArray("tools") {
            registry.all().forEach { tool ->
                addJsonObject {
                    put("name", tool.name)
                    put("title", tool.title)
                    put("description", tool.description)
                    put("inputSchema", tool.inputSchema)
                    // Hints Claude uses to decide which calls need the user's approval
                    putJsonObject("annotations") {
                        put("title", tool.title)
                        put("readOnlyHint", tool.readOnly)
                        put("destructiveHint", tool.destructive)
                        put("idempotentHint", tool.idempotent)
                        put("openWorldHint", tool.openWorld)
                    }
                }
            }
        }
    }

    // Run one tool, record it in the history, and wrap its output in the MCP "content" format
    private suspend fun callTool(params: JsonObject?): JsonObject {
        val name = params?.get("name")?.jsonPrimitive?.contentOrNull
            ?: throw IllegalArgumentException("Missing tool name")
        val args = params["arguments"]?.jsonObject ?: JsonObject(emptyMap())

        val tool = registry.get(name)
        if (tool == null) {
            // Tell "switched off by the user" apart from "does not exist"
            val switchedOff = registry.isDisabled(name)
            val message = if (switchedOff) "Tool '$name' is switched off by the user in the app" else "Unknown tool: $name"
            if (switchedOff) activity?.recordBlocked(name, args, message)
            throw IllegalArgumentException(message)
        }

        val entryId = activity?.begin(tool, args)   // appears in the Activity screen as RUNNING
        val startedAt = System.nanoTime()
        var blocked = false

        // Tool failures are reported inside the result (isError), not as protocol errors.
        // Tools run on the IO dispatcher: file and network work must not block the server's threads.
        val result = try {
            withContext(Dispatchers.IO) { tool.execute(args) }
        } catch (e: SecurityException) {
            blocked = true   // read-only workspace, or a path outside the workspaces
            ToolResult("Tool failed: ${e.message}", isError = true)
        } catch (e: Exception) {
            ToolResult("Tool failed: ${e.message}", isError = true)
        }

        if (entryId != null) {
            activity?.finish(entryId, tool, result, blocked, (System.nanoTime() - startedAt) / 1_000_000)
        }

        return buildJsonObject {
            putJsonArray("content") {
                addJsonObject {
                    put("type", "text")
                    put("text", result.text)
                }
            }
            put("isError", result.isError)
        }
    }

    // Small helpers to build success / error responses
    private fun ok(id: JsonElement?, result: JsonElement) = JsonRpcResponse(id = id, result = result)
    private fun fail(id: JsonElement?, code: Int, msg: String) =
        JsonRpcResponse(id = id, error = JsonRpcError(code, msg))
}