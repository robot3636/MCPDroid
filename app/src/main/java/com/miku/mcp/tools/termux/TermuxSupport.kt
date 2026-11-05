package com.miku.mcp.tools.termux

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

// One Termux build (the official app or a fork) that accepts RUN_COMMAND intents
data class TermuxVariant(
    val packageName: String,
    val label: String,
    val versionName: String?,
    val serviceClass: String,   // fully qualified class of its RunCommandService
    val permission: String,     // permission callers must hold, e.g. com.termux.permission.RUN_COMMAND
    val constPrefix: String     // prefix of its intent action and extras, e.g. com.termux
)

// Whether AI MCP may call the Termux service
enum class PermissionState {
    GRANTED,
    DENIED,         // declared in our manifest but not granted by the user yet
    NOT_DECLARED    // this build of AI MCP does not declare the permission the variant needs
}

object TermuxSupport {
    const val OFFICIAL_PACKAGE = "com.termux"
    const val RELEASES_URL = "https://github.com/termux/termux-app/releases"
    const val REPOSITORY_URL = "https://github.com/termux/termux-app"

    private const val PERMISSION_SUFFIX = ".permission.RUN_COMMAND"

    // Paste this into Termux: it turns on "allow-external-apps" (the switch Termux requires
    // before it accepts commands from other apps) and reloads the settings
    const val SETUP_COMMAND =
        "mkdir -p ~/.termux && touch ~/.termux/termux.properties && " +
        "sed -i '/^[[:space:]]*allow-external-apps[[:space:]]*=/d' ~/.termux/termux.properties && " +
        "echo 'allow-external-apps = true' >> ~/.termux/termux.properties && " +
        "(termux-reload-settings || true) && echo 'AI MCP: Termux is ready'"

    // Optional: lets Termux commands reach shared storage (shows a system permission dialog)
    const val STORAGE_COMMAND = "termux-setup-storage"

    // Reads the Termux build installed under this package name.
    // Returns null when it is missing or has no RunCommandService we can talk to.
    fun resolve(context: Context, packageName: String): TermuxVariant? {
        val pm = context.packageManager
        val info = try {
            pm.getPackageInfo(packageName, PackageManager.GET_SERVICES)
        } catch (e: Exception) {
            return null   // not installed, or hidden by package visibility
        }

        // The service class and the permission it demands come from the app itself,
        // so forks with other names work too
        val service = info.services
            ?.firstOrNull { it.name.endsWith(".RunCommandService") && !it.permission.isNullOrBlank() }
            ?: return null
        val permission = service.permission ?: return null
        if (!permission.endsWith(PERMISSION_SUFFIX)) return null

        val label = info.applicationInfo?.let { pm.getApplicationLabel(it).toString() } ?: packageName
        return TermuxVariant(
            packageName = packageName,
            label = label,
            versionName = info.versionName,
            serviceClass = service.name,
            permission = permission,
            // "com.termux.permission.RUN_COMMAND" -> "com.termux": the action and extras use this prefix
            constPrefix = permission.removeSuffix(PERMISSION_SUFFIX)
        )
    }

    // Installed Termux builds: the official one plus any app whose package name contains "termux"
    fun detectInstalled(context: Context): List<TermuxVariant> {
        val candidates = linkedSetOf(OFFICIAL_PACKAGE)
        try {
            context.packageManager.getInstalledApplications(0)
                .filter { it.packageName.contains("termux", ignoreCase = true) }
                .forEach { candidates += it.packageName }
        } catch (_: Exception) {
            // listing packages can fail on some devices: the official package is still checked
        }
        // Add-ons such as Termux:API have no RunCommandService, so resolve() filters them out
        return candidates.mapNotNull { resolve(context, it) }
    }

    fun permissionState(context: Context, permission: String): PermissionState {
        val declared = try {
            context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
                .requestedPermissions?.contains(permission) == true
        } catch (e: Exception) {
            false
        }
        return when {
            !declared -> PermissionState.NOT_DECLARED
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED ->
                PermissionState.GRANTED
            else -> PermissionState.DENIED
        }
    }
}