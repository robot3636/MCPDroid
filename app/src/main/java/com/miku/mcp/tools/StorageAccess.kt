package com.miku.mcp.tools

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import java.io.File

// Helpers for the "all files" permission
object StorageAccess {

    // Android 11+: the special "All files access"; older versions: the classic storage permissions
    fun hasAllFilesAccess(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            listOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE).all {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            }
        }

    // Opens this app's own "All files access" page
    @RequiresApi(Build.VERSION_CODES.R)
    fun appAccessIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))

    // Fallback: the general list of apps that may have this permission
    @RequiresApi(Build.VERSION_CODES.R)
    fun generalAccessIntent(): Intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)

    // The root of the phone's shared storage (what the file manager shows as "Internal storage")
    fun sharedStorageRoot(): File = Environment.getExternalStorageDirectory()
}

// Turns the result of the system folder picker into a real path that java.io.File can use
object FolderPicker {
    private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"

    // Returns null for locations that are not plain folders (Google Drive, Downloads provider...)
    fun treeUriToPath(uri: Uri): String? {
        if (uri.authority != EXTERNAL_STORAGE_AUTHORITY) return null
        val docId = try { DocumentsContract.getTreeDocumentId(uri) } catch (e: Exception) { return null }

        // The id looks like "primary:Documents/Notes" or "1234-ABCD:Backup" (SD card)
        val volume = docId.substringBefore(':')
        val relative = docId.substringAfter(':', "")
        val base = when (volume) {
            "primary" -> Environment.getExternalStorageDirectory().path
            "home" -> File(Environment.getExternalStorageDirectory(), "Documents").path   // the "Documents" root
            else -> "/storage/$volume"                                                      // SD card or USB drive
        }
        return if (relative.isEmpty()) base else "$base/$relative"
    }
}