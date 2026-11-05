package com.miku.mcp.tools

import java.io.File

// Safety limits so no single call can exhaust memory or storage
// (the download limit is a user setting now, see ToolSettings)
object Limits {
    const val MAX_READ_BYTES = 1L * 1024 * 1024    // 1 MB per read call
    const val MAX_WRITE_BYTES = 5L * 1024 * 1024   // 5 MB per write call
}

// A path Claude asked for, resolved to a real file inside one workspace
class ResolvedPath(val workspace: Workspace, val root: File, val file: File) {
    val isRoot: Boolean get() = file == root

    // The path as Claude sees it: "alias/relative/path"
    val display: String
        get() {
            val relative = file.relativeTo(root).path
            return if (relative.isEmpty()) workspace.alias else "${workspace.alias}/$relative"
        }
}

// Turns "alias/some/path" into a real file, enforcing workspace boundaries and permissions
class WorkspaceFs(private val settings: ToolSettings) {

    fun workspaces(): List<Workspace> = settings.state.value.workspaces

    // "", "." and "/" mean the virtual folder that contains all the workspaces
    fun isVirtualRoot(path: String): Boolean =
        path.trim().trim('/').let { it.isEmpty() || it == "." }

    // Resolves a path for READING
    fun resolve(path: String): ResolvedPath {
        if (isVirtualRoot(path)) {
            throw IllegalArgumentException("The path must start with a workspace name. ${availableText()}")
        }
        val cleaned = path.trim().removePrefix("./").trimStart('/')
        val alias = cleaned.substringBefore('/')
        val rest = cleaned.substringAfter('/', "")

        val workspace = workspaces().firstOrNull { it.alias == alias }
            ?: throw IllegalArgumentException("Unknown workspace '$alias'. ${availableText()}")

        // Canonical root: absolute path with symlinks and ".." already resolved
        val root = File(workspace.path).canonicalFile
        if (!root.isDirectory || !root.canRead()) {
            throw IllegalStateException(
                "Workspace '${workspace.alias}' is not accessible. The folder may be gone, " +
                    "or the app lacks 'All files access'. The user can fix this in the app."
            )
        }

        // canonicalFile resolves "..", ".", and symlinks, so escape tricks are neutralised
        val target = File(root, rest).canonicalFile
        val inside = target == root || target.path.startsWith(root.path + File.separator)
        if (!inside) throw SecurityException("Path escapes the workspace: $path")
        return ResolvedPath(workspace, root, target)
    }

    // Resolves a path for WRITING: the workspace must allow it
    fun resolveWritable(path: String): ResolvedPath {
        val resolved = resolve(path)
        if (!resolved.workspace.writable) {
            throw SecurityException(
                "Workspace '${resolved.workspace.alias}' is read-only. The user can allow writing in the app."
            )
        }
        return resolved
    }

    // Short hint appended to error messages
    private fun availableText(): String {
        val list = workspaces()
        return if (list.isEmpty()) "No workspace is configured; the user must add one in the app."
        else "Available workspaces: ${list.joinToString(", ") { it.alias }}."
    }

    // Shown to Claude in the connection handshake
    fun describe(): String {
        val list = workspaces()
        if (list.isEmpty()) return "No workspace is configured yet; the user must add one in the app."
        val names = list.joinToString(", ") { "${it.alias} (${if (it.writable) "read-write" else "read-only"})" }
        return "Workspaces: $names. Every path starts with a workspace name, for example ${list.first().alias}/folder/file.txt."
    }
}