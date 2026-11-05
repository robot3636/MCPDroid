package com.miku.mcp.tunnel

import android.content.Context
import android.util.Log
import com.miku.mcp.service.LogBuffer
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

// Runs the (pre-patched) official cloudflared executable as a child process
class CloudflaredTunnel(
    private val context: Context,
    private val mode: Mode,
    private val token: String?,            // required for NAMED
    private val publicHostname: String?,   // required for NAMED (set in the Cloudflare dashboard)
    private val onExit: (String) -> Unit   // called if the process dies after connecting
) : TunnelProvider {

    enum class Mode { QUICK, NAMED }

    private var process: Process? = null
    @Volatile private var stopping = false

    override fun start(localPort: Int): String {
        if (mode == Mode.NAMED) {
            require(!token.isNullOrBlank()) { "Tunnel token is missing" }
            require(!publicHostname.isNullOrBlank()) { "Public hostname is missing" }
        }

        // Visible separator so consecutive runs are easy to tell apart in the log window
        log("--- starting cloudflared (${mode.name.lowercase()} mode) ---")

        // The binary ships as libcloudflared.so and is extracted to the native library folder
        val binary = File(context.applicationInfo.nativeLibraryDir, "libcloudflared.so")
        check(binary.exists()) { "libcloudflared.so not found in ${binary.parent}" }

        // DNS config fed to the process through stdin (the patched binary reads /proc/self/fd/0)
        val resolvConf = File(context.filesDir, "resolv.conf")
        resolvConf.writeText("nameserver 1.1.1.1\nnameserver 8.8.8.8\n")

        // IPv4 + http2 avoid the QUIC/IPv6 problems seen on many Android networks
        val command = mutableListOf(
            binary.absolutePath, "tunnel", "--no-autoupdate",
            "--edge-ip-version", "4", "--protocol", "http2"
        )
        if (mode == Mode.QUICK) {
            // Quick tunnel: no account, random *.trycloudflare.com URL
            command += listOf("--url", "http://127.0.0.1:$localPort")
        } else {
            // Named tunnel: routing (hostname -> local port) is configured in the Cloudflare dashboard
            command += listOf("run", "--token", token.orEmpty())
        }

        val builder = ProcessBuilder(command)
        builder.environment()["GODEBUG"] = "netdns=go"  // force Go's own DNS resolver

        // The Linux build of Go looks for trusted CA certificates in /etc/ssl, which does not exist
        // on Android. Point it at Android's own certificate store instead (colon-separated;
        // folders that do not exist on this Android version are ignored).
        
        builder.environment()["SSL_CERT_DIR"] =
            "/apex/com.android.conscrypt/cacerts:/system/etc/security/cacerts"

        builder.redirectInput(resolvConf)
        builder.redirectErrorStream(true)                // merge stderr into stdout (cloudflared logs to stderr)
        val started = builder.start()
        process = started

        // Normalize the hostname the user typed (strip scheme and trailing slash)
        val hostname = publicHostname.orEmpty()
            .removePrefix("https://").removePrefix("http://").trimEnd('/')

        // The reader thread completes this future once the tunnel is really connected
        val result = CompletableFuture<String>()
        Thread { pumpOutput(started, hostname, result) }.apply {
            name = "cloudflared-output"
            isDaemon = true
            start()
        }

        return try {
            result.get(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (e: ExecutionException) {
            stop()
            throw IllegalStateException(e.cause?.message ?: "cloudflared failed")
        } catch (e: TimeoutException) {
            stop()
            throw IllegalStateException("Timed out waiting for the tunnel to connect")
        }
    }

    // Reads cloudflared's log lines until the process ends. It must keep draining the pipe,
    // otherwise cloudflared would block once the pipe buffer fills up.
    private fun pumpOutput(p: Process, hostname: String, result: CompletableFuture<String>) {
        var quickUrl: String? = null
        var registered = false
        val tail = ArrayDeque<String>()   // last few lines, used to explain failures

        try {
            p.inputStream.bufferedReader().useLines { lines ->
                for (rawLine in lines) {
                    // Never let the tunnel token reach the screen or logcat
                    val line = redact(rawLine)
                    log(line)

                    tail.addLast(line)
                    if (tail.size > 5) tail.removeFirst()

                    // Quick mode prints the public URL somewhere in its banner
                    if (mode == Mode.QUICK && quickUrl == null) {
                        quickUrl = QUICK_URL.find(line)?.value
                    }
                    // This line means the connection to Cloudflare's edge is up
                    if ("Registered tunnel connection" in line) registered = true

                    if (registered && !result.isDone) {
                        val url = if (mode == Mode.QUICK) quickUrl else "https://$hostname"
                        if (url != null) result.complete(url)
                    }
                }
            }
        } catch (_: Exception) {
            // stream closed because the process ended
        }

        log("--- cloudflared process ended ---")

        val reason = tail.joinToString(" | ")
        if (!result.isDone) {
            result.completeExceptionally(IllegalStateException("cloudflared exited: $reason"))
        } else if (!stopping) {
            onExit(reason)   // the tunnel died on its own after connecting
        }
    }

    override fun stop() {
        stopping = true
        val p = process ?: return
        process = null
        p.destroy()
        try {
            // Give it a moment to exit cleanly, then force-kill
            if (!p.waitFor(2, TimeUnit.SECONDS)) p.destroyForcibly()
        } catch (_: InterruptedException) {
            p.destroyForcibly()
        }
    }

    // Sends a line to both logcat and the in-app log window
    private fun log(line: String) {
        Log.d(TAG, line)
        LogBuffer.append(line)
    }

    // Replaces every occurrence of the tunnel token with *** before the line is shown or stored
    private fun redact(line: String): String =
        if (!token.isNullOrBlank()) line.replace(token, "***") else line

    private companion object {
        const val TAG = "CloudflaredTunnel"
        const val CONNECT_TIMEOUT_SECONDS = 45L
        val QUICK_URL = Regex("https://[a-z0-9-]+\\.trycloudflare\\.com")
    }
}