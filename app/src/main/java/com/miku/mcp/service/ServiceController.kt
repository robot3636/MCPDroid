package com.miku.mcp.service

import android.content.Context
import androidx.core.content.ContextCompat

// The only place the UI uses to talk to the foreground service
object ServiceController {

    // Starting actions must go through startForegroundService (the service promotes itself at once)
    fun startServer(context: Context) = ContextCompat.startForegroundService(
        context, McpForegroundService.intent(context, McpForegroundService.ACTION_START_SERVER)
    )

    fun startTunnel(context: Context) = ContextCompat.startForegroundService(
        context, McpForegroundService.intent(context, McpForegroundService.ACTION_START_TUNNEL)
    )

    // Stopping the server also stops the tunnel, because the tunnel forwards to the server
    fun stopServer(context: Context) {
        context.startService(McpForegroundService.intent(context, McpForegroundService.ACTION_STOP_SERVER))
    }

    // Stops only the tunnel; the local server keeps running
    fun stopTunnel(context: Context) {
        context.startService(McpForegroundService.intent(context, McpForegroundService.ACTION_STOP_TUNNEL))
    }
}