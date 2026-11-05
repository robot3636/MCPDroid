package com.miku.mcp.tools.termux

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

// What came back from Termux. When `error` is set, the command did not run (or its result was lost).
data class TermuxResult(
    val exitCode: Int? = null,
    val stdout: String = "",
    val stderr: String = "",
    val stdoutLength: Int? = null,   // original length, when Termux had to truncate the output
    val stderrLength: Int? = null,
    val error: String? = null
) {
    companion object {
        fun failure(message: String) = TermuxResult(error = message)
    }
}

// Extra that tells our receiver which waiting command a result belongs to
internal const val EXTRA_EXECUTION_ID = "ai_mcp_execution_id"
private const val ACTION_RESULT = "com.miku.mcp.action.TERMUX_RESULT"
private const val RESPONSE_GRACE_SECONDS = 15   // extra wait on top of the command's own time limit

// Sends a command to Termux and waits for its result
object TermuxRunner {

    private val nextId = AtomicInteger(5000)
    // Commands that were sent and are waiting for Termux to answer
    private val waiting = ConcurrentHashMap<Int, CompletableDeferred<TermuxResult>>()

    // Runs `command` with bash inside Termux, in the background, with a time limit
    suspend fun run(
        context: Context,
        variant: TermuxVariant,
        command: String,
        workdir: String?,
        timeoutSeconds: Int
    ): TermuxResult {
        val app = context.applicationContext
        val id = nextId.incrementAndGet()
        val deferred = CompletableDeferred<TermuxResult>()
        waiting[id] = deferred

        try {
            // Termux keeps its files under /data/data/<package>/files
            val base = "/data/data/${variant.packageName}/files"
            val bin = "$base/usr/bin"
            val home = "$base/home"
            val dir = when {
                workdir.isNullOrBlank() || workdir == "~" -> home
                workdir.startsWith("/") -> workdir
                workdir.startsWith("~/") -> "$home/${workdir.removePrefix("~/")}"
                else -> "$home/$workdir"
            }

            // Termux sends the result to this PendingIntent, which opens our receiver.
            // It must be mutable so Termux can attach the result, and unique per command.
            val resultIntent = Intent(app, TermuxResultReceiver::class.java)
                .setAction(ACTION_RESULT)
                .putExtra(EXTRA_EXECUTION_ID, id)
            var flags = PendingIntent.FLAG_ONE_SHOT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) flags = flags or PendingIntent.FLAG_MUTABLE
            val pendingIntent = PendingIntent.getBroadcast(app, id, resultIntent, flags)

            val p = variant.constPrefix
            val intent = Intent().apply {
                setClassName(variant.packageName, variant.serviceClass)
                action = "$p.RUN_COMMAND"
                // "timeout" ends the command after the limit (sending KILL 5 seconds later if needed)
                putExtra("$p.RUN_COMMAND_PATH", "$bin/timeout")
                putExtra(
                    "$p.RUN_COMMAND_ARGUMENTS",
                    arrayOf("-k", "5", timeoutSeconds.toString(), "$bin/bash", "-c", command)
                )
                putExtra("$p.RUN_COMMAND_WORKDIR", dir)
                putExtra("$p.RUN_COMMAND_BACKGROUND", true)   // no terminal window, separate stdout and stderr
                putExtra("$p.RUN_COMMAND_COMMAND_LABEL", "AI MCP")
                putExtra("$p.RUN_COMMAND_PENDING_INTENT", pendingIntent)
            }

            val started = try {
                app.startService(intent)
            } catch (e: SecurityException) {
                return TermuxResult.failure("Android refused to call Termux: ${e.message}")
            } catch (e: Exception) {
                return TermuxResult.failure("Could not start the Termux service: ${e.message}")
            }
            if (started == null) {
                return TermuxResult.failure("The Termux service was not found. Is Termux still installed?")
            }

            val result = withTimeoutOrNull((timeoutSeconds + RESPONSE_GRACE_SECONDS) * 1000L) { deferred.await() }
            return result ?: TermuxResult.failure(
                "Termux did not answer in time. Make sure allow-external-apps is enabled in Termux " +
                    "and that Termux is not restricted by battery optimization."
            )
        } finally {
            waiting.remove(id)
        }
    }

    // Called by the receiver when Termux answers
    internal fun complete(id: Int, result: TermuxResult) {
        waiting[id]?.complete(result)
    }
}

// Receives Termux's answer, turns it into a TermuxResult and hands it to the waiting command
class TermuxResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra(EXTRA_EXECUTION_ID, -1)
        val bundle = intent.getBundleExtra("result")
        val result = if (bundle == null) {
            TermuxResult.failure("Termux sent no result (extras: ${intent.extras?.keySet()})")
        } else {
            parse(bundle)
        }
        TermuxRunner.complete(id, result)
    }

    private fun parse(bundle: Bundle): TermuxResult {
        // "err" is Activity.RESULT_OK (-1) when Termux itself raised no internal error
        val err = bundle.getInt("err", -1)
        if (err != -1) {
            return TermuxResult.failure("Termux reported an error ($err): ${bundle.getString("errmsg").orEmpty()}")
        }
        return TermuxResult(
            exitCode = if (bundle.containsKey("exitCode")) bundle.getInt("exitCode") else null,
            stdout = bundle.getString("stdout").orEmpty(),
            stderr = bundle.getString("stderr").orEmpty(),
            stdoutLength = bundle.getString("stdout_original_length")?.toIntOrNull(),
            stderrLength = bundle.getString("stderr_original_length")?.toIntOrNull()
        )
    }
}