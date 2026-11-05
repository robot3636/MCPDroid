package com.miku.mcp.activity

import android.content.Context
import com.miku.mcp.mcp.McpJson
import com.miku.mcp.tools.McpTool
import com.miku.mcp.tools.ToolResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

// Lifecycle of one tool call
enum class ActivityStatus { RUNNING, SUCCESS, ERROR, BLOCKED }

// One row of the history
@Serializable
data class ActivityEntry(
    val id: Long,
    val startedAt: Long,          // epoch milliseconds
    val tool: String,             // technical name, e.g. "write_file"
    val title: String,            // friendly name, e.g. "Write file"
    val summary: String,          // arguments with private data removed
    val status: ActivityStatus,
    val durationMs: Long = 0,
    val output: String = ""       // short result text, or the error message
)

// Process-wide history of tool calls: the MCP handler writes it, the Activity screen observes it
class ActivityLog private constructor(context: Context) {

    private val file = File(context.filesDir, "activity.json")
    private val writer = Executors.newSingleThreadExecutor()   // disk writes never block a tool call
    private val nextId = AtomicLong(System.currentTimeMillis())

    private val _entries = MutableStateFlow(load())
    val entries: StateFlow<List<ActivityEntry>> = _entries.asStateFlow()   // newest first

    // ---------- Recording ----------

    // A tool call starts: shows up immediately as RUNNING and returns its id
    fun begin(tool: McpTool, args: JsonObject): Long {
        val id = nextId.incrementAndGet()
        add(
            ActivityEntry(
                id = id,
                startedAt = System.currentTimeMillis(),
                tool = tool.name,
                title = tool.title,
                summary = summarize(args),
                status = ActivityStatus.RUNNING
            )
        )
        return id
    }

    // The call ended: update the same row with the outcome
    fun finish(id: Long, tool: McpTool, result: ToolResult, blocked: Boolean, durationMs: Long) {
        val status = when {
            blocked -> ActivityStatus.BLOCKED
            result.isError -> ActivityStatus.ERROR
            else -> ActivityStatus.SUCCESS
        }
        val output = when {
            result.isError -> result.text
            tool.recordsOutput -> result.text
            else -> "${result.text.length} characters returned (content is not recorded)"
        }.take(MAX_OUTPUT_CHARS)

        _entries.update { list ->
            list.map { if (it.id == id) it.copy(status = status, durationMs = durationMs, output = output) else it }
        }
        persist()
    }

    // A call that never ran because the tool is switched off
    fun recordBlocked(toolName: String, args: JsonObject, message: String) {
        add(
            ActivityEntry(
                id = nextId.incrementAndGet(),
                startedAt = System.currentTimeMillis(),
                tool = toolName,
                title = toolName,
                summary = summarize(args),
                status = ActivityStatus.BLOCKED,
                output = message
            )
        )
        persist()
    }

    fun clear() {
        _entries.value = emptyList()
        persist()
    }

    // ---------- Internals ----------

    // Newest first, capped so the history cannot grow forever
    private fun add(entry: ActivityEntry) {
        _entries.update { (listOf(entry) + it).take(MAX_ENTRIES) }
    }

    // Arguments as short text, without anything private:
    // file content becomes a character count, and URLs lose their query string (it can hold secrets)
    private fun summarize(args: JsonObject): String =
        args.entries.joinToString("   ") { (key, value) ->
            val raw = (value as? JsonPrimitive)?.content ?: value.toString()
            val shown = when (key) {
                "content" -> "<${raw.length} chars>"
                "url" -> raw.substringBefore('?')
                else -> raw
            }
            "$key=${shown.take(if (key == "command") 400 else 80)}"   // shell commands are shown (almost) in full
        }.ifEmpty { "(no arguments)" }

    private fun persist() {
        val snapshot = _entries.value
        writer.execute {
            try {
                file.writeText(McpJson.encodeToString(snapshot))
            } catch (_: Exception) {
                // losing one history write is not worth crashing a tool call
            }
        }
    }

    private fun load(): List<ActivityEntry> = try {
        if (!file.exists()) {
            emptyList()
        } else {
            McpJson.decodeFromString<List<ActivityEntry>>(file.readText()).map {
                // A call that was still running when the app died can never finish
                if (it.status == ActivityStatus.RUNNING) {
                    it.copy(status = ActivityStatus.ERROR, output = "Interrupted: the app stopped before the tool finished")
                } else it
            }
        }
    } catch (e: Exception) {
        emptyList()   // unreadable file: start with an empty history
    }

    companion object {
        private const val MAX_ENTRIES = 300
        private const val MAX_OUTPUT_CHARS = 400

        @Volatile private var instance: ActivityLog? = null

        // One instance per process, shared by the service and the UI
        fun get(context: Context): ActivityLog =
            instance ?: synchronized(this) {
                instance ?: ActivityLog(context.applicationContext).also { instance = it }
            }
    }
}