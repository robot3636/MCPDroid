package com.miku.mcp.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.miku.mcp.MainActivity
import com.miku.mcp.mcp.McpHandler
import com.miku.mcp.security.SecretStore
import com.miku.mcp.server.McpServer
import com.miku.mcp.server.OAuthStore
import com.miku.mcp.server.TokenStore
import com.miku.mcp.settings.AppSettings
import com.miku.mcp.tools.ToolHub
import com.miku.mcp.tools.ToolRegistry
import com.miku.mcp.tools.registerFileTools
import com.miku.mcp.tunnel.CloudflaredTunnel
import com.miku.mcp.tunnel.TunnelProvider
import com.miku.mcp.activity.ActivityLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

// Hosts the MCP server and the tunnel. Each can be started and stopped on its own.
class McpForegroundService : Service() {

    // Background scope for work that must not run on the main thread
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var server: McpServer? = null
    private var tunnel: TunnelProvider? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var settings: AppSettings

    // Each tunnel start gets a number; results of an older (cancelled) attempt are ignored
    private val tunnelGeneration = AtomicInteger(0)

    // The notification may only be updated while the service is really in the foreground
    @Volatile private var inForeground = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        settings = AppSettings(applicationContext)
        createChannel()

        // Keep the notification text in sync with the server and tunnel state
        scope.launch {
            combine(ServerState.status, ServerState.tunnel) { s, t -> s to t }
                .collect { (s, t) ->
                    if (inForeground) {
                        getSystemService(NotificationManager::class.java)
                            .notify(NOTIFICATION_ID, buildNotification(s, t))
                    }
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            // Stop everything (notification button, or the server toggle in the UI)
            ACTION_STOP, ACTION_STOP_SERVER -> {
                shutdown()
                return START_NOT_STICKY
            }

            // Stop only the tunnel; if the service was started just for this, close it
            ACTION_STOP_TUNNEL -> {
                stopTunnel()
                if (server == null) shutdown()
                return START_STICKY
            }

            ACTION_START_TUNNEL -> {
                enterForeground()   // required right after startForegroundService
                if (ServerState.status.value is ServerStatus.Running) {
                    startTunnel(settings.port)
                } else {
                    // The tunnel forwards to the server, so the server must run first
                    shutdown(keepError = true)
                    ServerState.setTunnel(TunnelStatus.Error("Start the server first"))
                    return START_NOT_STICKY
                }
                return START_STICKY
            }

            // ACTION_START_SERVER, or a restart by the system (null intent)
            else -> {
                enterForeground()
                if (server == null) startServer(settings.port)
                return START_STICKY
            }
        }
    }

    // Promotes the service to the foreground (Android requires this within seconds)
    private fun enterForeground() {
        // The type constant only exists on Android 14+; older versions use the manifest declaration
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID,
            buildNotification(ServerState.status.value, ServerState.tunnel.value), type
        )
        inForeground = true
    }

    // Builds the tool set and starts Ktor on a background thread
    private fun startServer(port: Int) {
        ServerState.set(ServerStatus.Starting)
        scope.launch {
            try {
                val token = TokenStore(applicationContext).getOrCreate()
                // The registry is shared with the UI; tool switches and workspaces apply live
                val registry = ToolHub.registry(applicationContext)
                // OAuthStore is shared with the UI (same process)
                val newServer = McpServer(
                    port, token,
                    // The history is shared with the UI (same process)
                    McpHandler(registry, ActivityLog.get(applicationContext)) { ToolHub.workspaceSummary(applicationContext) },
                    OAuthStore.get(applicationContext)
                )
                newServer.start()   // throws a readable error if the port is busy
                server = newServer

                acquireWakeLock()
                ServerState.set(ServerStatus.Running(port))

                // Optional: bring the tunnel up automatically
                if (settings.tunnelAutoStart) startTunnel(port)
            } catch (e: Exception) {
                ServerState.set(ServerStatus.Error(e.message ?: e.toString()))
                shutdown(keepError = true)
            }
        }
    }

    // Starts cloudflared in the saved mode; failures never stop the local server
    private fun startTunnel(port: Int) {
        if (tunnel != null) return   // already running or connecting

        val secrets = SecretStore(applicationContext)
        val mode = if (secrets.get(SecretStore.TUNNEL_MODE) == "named") {
            CloudflaredTunnel.Mode.NAMED
        } else {
            CloudflaredTunnel.Mode.QUICK   // default, also covers the old "off" value
        }

        val generation = tunnelGeneration.incrementAndGet()
        ServerState.setTunnel(TunnelStatus.Connecting)

        val newTunnel = CloudflaredTunnel(
            context = applicationContext,
            mode = mode,
            token = secrets.get(SecretStore.CF_TOKEN),
            publicHostname = secrets.get(SecretStore.CF_HOSTNAME),
            // The process died on its own after connecting (network loss, revoked token...)
            onExit = { reason ->
                if (tunnelGeneration.get() == generation) {
                    tunnel = null
                    ServerState.setTunnel(TunnelStatus.Error("Tunnel stopped: $reason"))
                }
            }
        )
        tunnel = newTunnel   // visible immediately so "Stop" can cancel a connection attempt

        scope.launch {
            try {
                val url = newTunnel.start(port)   // blocks until connected or failed
                if (tunnelGeneration.get() == generation) ServerState.setTunnel(TunnelStatus.Connected(url))
            } catch (t: Throwable) {
                // Ignore failures of an attempt the user already cancelled
                if (tunnelGeneration.get() == generation) {
                    tunnel = null
                    ServerState.setTunnel(TunnelStatus.Error("${t.javaClass.simpleName}: ${t.message}"))
                }
            }
        }
    }

    // Stops only the tunnel
    private fun stopTunnel() {
        tunnelGeneration.incrementAndGet()   // invalidates any attempt still in progress
        val t = tunnel
        tunnel = null
        ServerState.setTunnel(TunnelStatus.Off)
        if (t != null) Thread { t.stop() }.start()   // stop() blocks briefly
    }

    // Stops tunnel and server, removes the notification, ends the service
    private fun shutdown(keepError: Boolean = false) {
        inForeground = false
        stopTunnel()
        val s = server
        server = null
        if (s != null) Thread { s.stop() }.start()
        releaseWakeLock()
        if (!keepError) ServerState.set(ServerStatus.Stopped)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // Safety net: Android can destroy the service without going through shutdown()
    override fun onDestroy() {
        inForeground = false
        scope.cancel()
        tunnelGeneration.incrementAndGet()
        val t = tunnel
        val s = server
        tunnel = null
        server = null
        if (t != null || s != null) Thread { t?.stop(); s?.stop() }.start()
        releaseWakeLock()
        ServerState.setTunnel(TunnelStatus.Off)
        if (ServerState.status.value !is ServerStatus.Error) ServerState.set(ServerStatus.Stopped)
        super.onDestroy()
    }

    // Partial wake lock: keeps the CPU running with the screen off (costs battery)
    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AiMcp::Server").apply { acquire() }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "MCP server", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    // One line summarizing both the server and the tunnel
    private fun buildNotification(server: ServerStatus, tunnel: TunnelStatus): Notification {
        val serverText = when (server) {
            is ServerStatus.Running -> "Server on port ${server.port}"
            ServerStatus.Starting -> "Server starting..."
            is ServerStatus.Error -> "Server error"
            ServerStatus.Stopped -> "Server stopped"
        }
        val tunnelText = when (tunnel) {
            is TunnelStatus.Connected -> "Tunnel connected"
            TunnelStatus.Connecting -> "Tunnel connecting..."
            is TunnelStatus.Error -> "Tunnel error"
            TunnelStatus.Off -> null
        }

        // Tapping the notification opens the app; the button stops everything
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(this, 1, intent(this, ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)   // placeholder: replace with your own icon later
            .setContentTitle("AI MCP")
            .setContentText(listOfNotNull(serverText, tunnelText).joinToString(" · "))
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    companion object {
        const val ACTION_START_SERVER = "com.miku.mcp.action.START_SERVER"
        const val ACTION_STOP_SERVER = "com.miku.mcp.action.STOP_SERVER"
        const val ACTION_START_TUNNEL = "com.miku.mcp.action.START_TUNNEL"
        const val ACTION_STOP_TUNNEL = "com.miku.mcp.action.STOP_TUNNEL"
        const val ACTION_STOP = "com.miku.mcp.action.STOP"
        private const val CHANNEL_ID = "mcp_server"
        private const val NOTIFICATION_ID = 1

        fun intent(context: Context, action: String): Intent =
            Intent(context, McpForegroundService::class.java).setAction(action)
    }
}