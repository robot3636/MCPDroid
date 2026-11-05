package com.miku.mcp.tools

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.net.InetAddress
import java.net.URI

class DownloadFileTool(
    private val fs: WorkspaceFs,
    private val settings: ToolSettings   // provides the user's maximum download size
) : McpTool {
    override val name = "download_file"
    override val title = "Download file"
    override val openWorld = true
    override val description =
        "Downloads a file from an http(s) URL into a read-write workspace. " +
        "Local and private network addresses are blocked."
    override val inputSchema = schemaOf(
        Param("url", "string", "The http or https URL to download", required = true),
        Param("path", "string", "Destination file. $PATH_HELP", required = true),
        Param("overwrite", "boolean", "Replace the file if it exists. Defaults to false")
    )

    // One client reused across downloads; the timeout stops a stalled server from hanging forever
    private val client = HttpClient(CIO) {
        engine { requestTimeout = 120_000 }
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        val url = args.requireString("url")
        val res = fs.resolveWritable(args.requireString("path"))   // rejects read-only workspaces
        val target = res.file
        val overwrite = args.bool("overwrite") ?: false

        // The limit is read now, so changing it in the app applies to the next download
        val maxMb = settings.state.value.maxDownloadMb
        val maxBytes = maxMb * 1024L * 1024L

        // Only plain web URLs are allowed (no file://, ftp://, etc.)
        val uri = try { URI(url) } catch (e: Exception) { return ToolResult("Invalid URL", true) }
        if (uri.scheme != "http" && uri.scheme != "https") return ToolResult("Only http/https URLs are allowed", true)
        val host = uri.host ?: return ToolResult("URL has no host", true)

        // SSRF guard: refuse targets on the phone itself or the local network
        // (a DNS-rebinding attacker could still bypass this check)
        val addresses = try { InetAddress.getAllByName(host) } catch (e: Exception) {
            return ToolResult("Cannot resolve host: $host", true)
        }
        if (addresses.any { it.isLoopbackAddress || it.isSiteLocalAddress || it.isLinkLocalAddress || it.isAnyLocalAddress }) {
            return ToolResult("Blocked: URL points to a local or private address", true)
        }

        if (target.isDirectory) return ToolResult("Destination is a directory", true)
        if (target.exists() && !overwrite) return ToolResult("File exists. Pass overwrite=true to replace it.", true)
        target.parentFile?.mkdirs()

        // Download into a temp file first so a failed download never leaves a half-written target
        val temp = File(target.path + ".part")
        val written = try {
            client.prepareGet(url).execute { response ->
                check(response.status.isSuccess()) { "Server returned HTTP ${response.status.value}" }

                val channel = response.bodyAsChannel()
                var total = 0L
                temp.outputStream().use { out ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val read = channel.readAvailable(buffer, 0, buffer.size)
                        if (read == -1) break   // end of stream
                        total += read
                        // Abort as soon as the size limit is crossed
                        check(total <= maxBytes) { "File exceeds the $maxMb MB limit" }
                        out.write(buffer, 0, read)
                    }
                }
                total
            }
        } catch (e: Exception) {
            temp.delete()
            return ToolResult("Download failed: ${e.message}", true)
        }

        // Swap the finished temp file into place
        if (target.exists()) target.delete()
        if (!temp.renameTo(target)) {
            temp.delete()
            return ToolResult("Could not save the downloaded file", true)
        }
        return ToolResult("Downloaded $written bytes to ${res.display}")
    }
}