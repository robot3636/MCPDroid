package com.miku.mcp.tools

import kotlinx.serialization.json.JsonObject
import java.io.RandomAccessFile
import java.time.Instant

// Shared help text for every "path" argument
const val PATH_HELP = "Path starting with a workspace name, for example 'app/notes/todo.txt'"

// Registers every file tool: six in the Files module, one in the Downloads module
fun ToolRegistry.registerFileTools(fs: WorkspaceFs, settings: ToolSettings) {
    register(ListFilesTool(fs), ModuleIds.FILES)
    register(ReadFileTool(fs), ModuleIds.FILES)
    register(WriteFileTool(fs), ModuleIds.FILES)
    register(CreateDirectoryTool(fs), ModuleIds.FILES)
    register(DeleteFileTool(fs), ModuleIds.FILES)
    register(FileInfoTool(fs), ModuleIds.FILES)
    register(DownloadFileTool(fs, settings), ModuleIds.DOWNLOADS)
}

// ---------- list_files ----------
class ListFilesTool(private val fs: WorkspaceFs) : McpTool {
    override val name = "list_files"
    override val title = "List files"
    override val readOnly = true
    override val destructive = false
    override val idempotent = true
    override val description =
        "Lists files and folders (not recursive). Use '.' to list the available workspaces."
    override val inputSchema = schemaOf(
        Param("path", "string", "$PATH_HELP. Defaults to '.', which lists the workspaces")
    )

    override suspend fun execute(args: JsonObject): ToolResult {
        val path = args.string("path") ?: "."

        // The virtual root contains the workspaces themselves
        if (fs.isVirtualRoot(path)) {
            val list = fs.workspaces()
            if (list.isEmpty()) return ToolResult("No workspace is configured. The user must add one in the app.")
            return ToolResult(list.joinToString("\n") {
                "[workspace] ${it.alias}/ (${if (it.writable) "read-write" else "read-only"})"
            })
        }

        val dir = fs.resolve(path)
        if (!dir.file.isDirectory) return ToolResult("Not a directory: ${dir.display}", true)

        // Folders first, then files, both sorted alphabetically
        val entries = dir.file.listFiles()
            ?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            ?: return ToolResult("Cannot list ${dir.display}", true)

        if (entries.isEmpty()) return ToolResult("(empty directory)")
        return ToolResult(entries.joinToString("\n") {
            if (it.isDirectory) "[dir]  ${it.name}/" else "[file] ${it.name} (${it.length()} bytes)"
        })
    }
}

// ---------- read_file ----------
class ReadFileTool(private val fs: WorkspaceFs) : McpTool {
    override val name = "read_file"
    override val title = "Read file"
    override val readOnly = true
    override val destructive = false
    override val idempotent = true
    override val recordsOutput = false   // file contents are never stored in the history
    override val description =
        "Reads a text file (UTF-8). Large files are read in chunks using offset and length (in bytes)."
    override val inputSchema = schemaOf(
        Param("path", "string", PATH_HELP, required = true),
        Param("offset", "integer", "Byte position to start reading from. Defaults to 0"),
        Param("length", "integer", "Max bytes to read. Defaults to and is capped at ${Limits.MAX_READ_BYTES}")
    )

    override suspend fun execute(args: JsonObject): ToolResult {
        val res = fs.resolve(args.requireString("path"))
        val file = res.file
        if (!file.isFile) return ToolResult("File not found: ${res.display}", true)

        val size = file.length()
        val offset = (args.long("offset") ?: 0L).coerceAtLeast(0L)
        val wanted = (args.long("length") ?: Limits.MAX_READ_BYTES).coerceIn(1L, Limits.MAX_READ_BYTES)

        // Bytes actually available from the offset, capped by the requested length
        val count = (size - offset).coerceIn(0L, wanted).toInt()
        val buffer = ByteArray(count)

        // RandomAccessFile jumps straight to the offset without reading what comes before
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(offset)
            raf.readFully(buffer)
        }

        var text = String(buffer, Charsets.UTF_8)
        // Tell Claude how to continue when the file was not fully read
        val next = offset + count
        if (next < size) text += "\n[read $count of $size bytes; continue with offset=$next]"
        return ToolResult(text)
    }
}

// ---------- write_file ----------
class WriteFileTool(private val fs: WorkspaceFs) : McpTool {
    override val name = "write_file"
    override val title = "Write file"
    override val description =
        "Creates a file or overwrites it with the given text. Set append=true to add to the end. " +
        "Missing parent folders are created. Only works in read-write workspaces."
    override val inputSchema = schemaOf(
        Param("path", "string", PATH_HELP, required = true),
        Param("content", "string", "Text to write (UTF-8)", required = true),
        Param("append", "boolean", "Append instead of overwrite. Defaults to false")
    )

    override suspend fun execute(args: JsonObject): ToolResult {
        val res = fs.resolveWritable(args.requireString("path"))   // rejects read-only workspaces
        val file = res.file
        val bytes = args.requireString("content").toByteArray(Charsets.UTF_8)
        val append = args.bool("append") ?: false

        if (bytes.size > Limits.MAX_WRITE_BYTES) return ToolResult("Content exceeds the write limit", true)
        if (file.isDirectory) return ToolResult("Path is a directory: ${res.display}", true)

        file.parentFile?.mkdirs()   // safe: the parent is inside the workspace too
        if (append) file.appendBytes(bytes) else file.writeBytes(bytes)
        return ToolResult("${if (append) "Appended" else "Wrote"} ${bytes.size} bytes to ${res.display}")
    }
}

// ---------- create_directory ----------
class CreateDirectoryTool(private val fs: WorkspaceFs) : McpTool {
    override val name = "create_directory"
    override val title = "Create folder"
    override val destructive = false
    override val idempotent = true
    override val description =
        "Creates a folder (and any missing parent folders). Only works in read-write workspaces."
    override val inputSchema = schemaOf(
        Param("path", "string", PATH_HELP, required = true)
    )

    override suspend fun execute(args: JsonObject): ToolResult {
        val res = fs.resolveWritable(args.requireString("path"))
        val dir = res.file
        if (dir.isFile) return ToolResult("A file already exists at that path", true)
        if (!dir.exists() && !dir.mkdirs()) return ToolResult("Could not create directory", true)
        return ToolResult("Directory ready: ${res.display}")
    }
}

// ---------- delete_file ----------
class DeleteFileTool(private val fs: WorkspaceFs) : McpTool {
    override val name = "delete_file"
    override val title = "Delete file or folder"
    override val description =
        "Deletes a file or folder. Non-empty folders need recursive=true. A workspace root cannot be deleted. " +
        "Only works in read-write workspaces."
    override val inputSchema = schemaOf(
        Param("path", "string", PATH_HELP, required = true),
        Param("recursive", "boolean", "Delete a non-empty folder with everything inside. Defaults to false")
    )

    override suspend fun execute(args: JsonObject): ToolResult {
        val res = fs.resolveWritable(args.requireString("path"))
        val file = res.file
        val recursive = args.bool("recursive") ?: false

        if (res.isRoot) return ToolResult("Refusing to delete a workspace root", true)
        if (!file.exists()) return ToolResult("Not found: ${res.display}", true)

        // Protect against accidental mass deletion
        if (file.isDirectory && !recursive && file.list()?.isNotEmpty() == true) {
            return ToolResult("Folder is not empty. Pass recursive=true to delete it.", true)
        }

        val deleted = if (file.isDirectory) file.deleteRecursively() else file.delete()
        return if (deleted) ToolResult("Deleted ${res.display}")
        else ToolResult("Could not delete ${res.display}", true)
    }
}

// ---------- file_info ----------
class FileInfoTool(private val fs: WorkspaceFs) : McpTool {
    override val name = "file_info"
    override val title = "File info"
    override val readOnly = true
    override val destructive = false
    override val idempotent = true
    override val description = "Returns type, size, and last-modified time of a file or folder."
    override val inputSchema = schemaOf(
        Param("path", "string", PATH_HELP, required = true)
    )

    override suspend fun execute(args: JsonObject): ToolResult {
        val res = fs.resolve(args.requireString("path"))
        val file = res.file
        if (!file.exists()) return ToolResult("Not found: ${res.display}", true)

        return ToolResult(
            """
            path: ${res.display}
            type: ${if (file.isDirectory) "directory" else "file"}
            size: ${file.length()} bytes
            modified: ${Instant.ofEpochMilli(file.lastModified())}
            readable: ${file.canRead()}
            writable: ${file.canWrite() && res.workspace.writable}
            """.trimIndent()
        )
    }
}