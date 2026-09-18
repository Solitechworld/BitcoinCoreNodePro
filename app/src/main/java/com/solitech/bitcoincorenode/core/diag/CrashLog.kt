package com.solitech.bitcoincorenode.core.diag

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes the last fatal error to a file so it survives the process dying.
 *
 * ## Why this exists
 *
 * When this app closes unexpectedly on someone's phone, the stack trace goes to
 * logcat — and logcat needs a computer, a cable, developer mode and `adb`. For
 * every user who is not sitting at a workstation, the crash is simply "it
 * closed", which is unactionable for them and unactionable in a bug report.
 *
 * So the trace is written to the app's private storage before the process goes,
 * and shown on the Node screen next launch with a copy button. One file, kept
 * only until it is dismissed.
 *
 * ## Deliberate restraint
 *
 * It records a stack trace, a timestamp and the device's model and Android
 * version, and nothing else. No wallet name, no address, no balance, no
 * identifier of any kind — a diagnostic the user is invited to paste into a
 * public issue tracker must be safe to paste into a public issue tracker.
 *
 * It is also written synchronously with no allocation-heavy work: this runs on
 * a process that is already dying, and anything clever here fails silently and
 * loses the trace entirely.
 */
object CrashLog {

    private const val FILE_NAME = "last-crash.txt"
    private const val MAX_BYTES = 64 * 1024

    /** Installs the process-wide handler. Call once, from Application.onCreate. */
    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { record(appContext, "Uncaught on thread ${thread.name}", error) }
            // Always hand back to the platform handler. Swallowing it would
            // leave a zombie process with a dead UI, which is worse than the
            // crash: the user taps a frozen app instead of relaunching a
            // working one.
            previous?.uncaughtException(thread, error)
        }
    }

    /** Records a failure that was caught and handled, but is still worth keeping. */
    fun record(context: Context, what: String, error: Throwable) {
        runCatching {
            val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
            val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            val text = buildString {
                appendLine("Bitcoin Core Node — crash report")
                appendLine("When    : $stamp")
                appendLine("Where   : $what")
                appendLine("Device  : ${android.os.Build.MODEL} (${android.os.Build.DEVICE})")
                appendLine("Android : ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
                appendLine("ABI     : ${android.os.Build.SUPPORTED_ABIS.firstOrNull()}")
                appendLine()
                append(trace.take(MAX_BYTES))
            }
            file(context).writeText(text)
        }
    }

    /** The stored report, or null if there is none. */
    fun read(context: Context): String? =
        file(context).takeIf { it.exists() }?.let { runCatching { it.readText() }.getOrNull() }

    fun clear(context: Context) {
        runCatching { file(context).delete() }
    }

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)
}
