package com.miku.mcp.tools.termux

import android.content.Context
import com.miku.mcp.tools.McpTool
import com.miku.mcp.tools.Param
import com.miku.mcp.tools.ToolResult
import com.miku.mcp.tools.ToolSettings
import com.miku.mcp.tools.bool
import com.miku.mcp.tools.long
import com.miku.mcp.tools.requireString
import com.miku.mcp.tools.schemaOf
import com.miku.mcp.tools.string
import kotlinx.serialization.json.JsonObject

// Lets Claude run shell commands in Termux. Off by default; see the Termux screen in the app.
class TermuxRunCommandTool(
    private val context: Context,
    private val settings: ToolSettings
) : McpTool {
    override val name = "run_termux_command"
    override val title = "Run Termux command"
    override val openWorld = true   // a shell can reach the internet; destructive stays true (it can delete)
    override val description =
        "Runs a shell command (bash) inside Termux on the user's phone and returns the exit code, stdout " +
        "and stderr. The command runs in the background with a time limit and cannot read keyboard input. " +
        "The working directory defaults to the Termux home folder. Termux is a Linux environment: " +
        "use its packages (pkg install ...) and standard tools."
    override val inputSchema = schemaOf(
        Param("command", "string", "The bash command line to run", required = true),
        Param("workdir", "string", "Working directory: absolute, or relative to the Termux home folder. Defaults to the home folder"),
        Param("timeout_seconds", "integer", "Time limit in seconds. Defaults to $DEFAULT_TIMEOUT_SECONDS, maximum $MAX_TIMEOUT_SECONDS")
    )

    override suspend fun execute(args: JsonObject): ToolResult {
        val command = args.requireString("command")
        if (command.isBlank()) return ToolResult("The command is empty", true)
        if (command.length > MAX_COMMAND_CHARS) return ToolResult("The command is longer than $MAX_COMMAND_CHARS characters", true)
        val timeout = (args.long("timeout_seconds") ?: DEFAULT_TIMEOUT_SECONDS.toLong())
            .coerceIn(1L, MAX_TIMEOUT_SECONDS.toLong()).toInt()

        // The setup is checked again on every call, so Claude gets a clear reason if it broke
        val packageName = settings.state.value.termuxPackage
        val variant = TermuxSupport.resolve(context, packageName)
            ?: return ToolResult(
                "Termux ($packageName) is not installed or is not compatible. " +
                    "Ask the user to install it and finish the setup in the app (Tools > Termux).",
                true
            )
        when (TermuxSupport.permissionState(context, variant.permission)) {
            PermissionState.GRANTED -> Unit
            PermissionState.DENIED -> return ToolResult(
                "AI MCP does not have the permission to run commands in Termux. " +
                    "Ask the user to grant it in the app (Tools > Termux, step 2).",
                true
            )
            PermissionState.NOT_DECLARED -> return ToolResult(
                "This build of AI MCP is missing the permission ${variant.permission}. It must be added to the manifest.",
                true
            )
        }

        return format(TermuxRunner.run(context, variant, command, args.string("workdir"), timeout), timeout)
    }

    // Turns Termux's answer into text for Claude
    private fun format(result: TermuxResult, timeout: Int): ToolResult {
        if (result.error != null) return ToolResult(result.error, true)

        val out = StringBuilder("exit code: ${result.exitCode ?: "unknown"}")
        if (result.exitCode == 124) out.append(" (timed out after ${timeout}s)")   // 124 is what "timeout" returns
        appendSection(out, "stdout", result.stdout, result.stdoutLength)
        appendSection(out, "stderr", result.stderr, result.stderrLength)

        // A non-zero exit code is reported as an error so Claude notices the command failed
        return ToolResult(out.toString(), isError = result.exitCode != 0)
    }

    // Adds one output section, keeping the END of long output (that is where errors usually are)
    private fun appendSection(out: StringBuilder, label: String, text: String, originalLength: Int?) {
        if (text.isEmpty()) return
        val shown = if (text.length > MAX_OUTPUT_CHARS) text.takeLast(MAX_OUTPUT_CHARS) else text
        out.append("\n--- $label ---\n")
        if (shown.length < text.length) out.append("[${text.length - shown.length} earlier characters omitted]\n")
        else if (originalLength != null && originalLength > text.length) {
            out.append("[Termux truncated this output: it had $originalLength characters]\n")
        }
        out.append(shown)
    }

    private companion object {
        const val DEFAULT_TIMEOUT_SECONDS = 60
        const val MAX_TIMEOUT_SECONDS = 300
        const val MAX_COMMAND_CHARS = 20_000
        const val MAX_OUTPUT_CHARS = 60_000
    }
}