package com.miku.mcp.tunnel

// Any tunnel service (ngrok now, maybe others later) implements this
interface TunnelProvider {
    // Opens the tunnel to a local port and returns the public base URL. Blocking: call off the main thread.
    fun start(localPort: Int): String

    // Closes the tunnel
    fun stop()
}