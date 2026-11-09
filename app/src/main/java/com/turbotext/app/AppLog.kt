package com.turbotext.app

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Keeps TurboText's own log on the phone, so a user can save a bug report
 * (Settings > Advanced > Save Bug Report) without a computer or adb.
 *
 * - Everything this process logs (TurboText's lines and the MMS
 *   library's) is copied from logcat into files/logs/app.log as it
 *   happens. An app may always read its own process's log.
 * - A crash's stack trace is written straight to the file before the app
 *   closes, since the logcat copy may not get to it in time.
 * - The file is rotated at [MAX_BYTES] (one older copy, app.log.1), so it
 *   stays around 2 MB.
 *
 * Logs name phone numbers and contacts, but no message text.
 */
object AppLog {
    private const val TAG = "TurboTextAppLog"
    private const val MAX_BYTES = 1024 * 1024L

    @Volatile private var dir: File? = null
    @Volatile private var version = "?"
    private val lock = Any()

    fun start(context: Context) {
        val logs = File(context.filesDir, "logs").apply { mkdirs() }
        dir = logs
        version = try { UpdateChecker.installedVersion(context) } catch (e: Exception) { "?" }
        installCrashHandler()
        Thread({ copyLogcat(logs) }, "TurboTextAppLog").apply {
            isDaemon = true
            start()
        }
    }

    /** Streams this process's logcat lines into the file for as long as
     *  the process runs. On this phone the stream keeps breaking ("read:
     *  unexpected EOF!"); it used to be given up after 5 breaks, and the
     *  bug report then missed everything after. Now it's always restarted
     *  — from the time of the last line copied, so nothing is lost — and
     *  each restart is noted in the file. */
    private fun copyLogcat(logs: File) {
        val pid = android.os.Process.myPid()
        append("---- TurboText started (pid $pid, v$version) ----")
        var lastStamp: String? = null
        var delayMs = 2_000L
        while (true) {
            val startedAt = System.currentTimeMillis()
            // Lines up to here were copied before the break; the restarted
            // stream repeats them.
            val copiedUntil = lastStamp
            try {
                val from = copiedUntil ?: "1" // first time: from now on
                val proc = ProcessBuilder("logcat", "-v", "threadtime", "--pid=$pid", "-T", from)
                    .redirectErrorStream(true).start()
                proc.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        val stamp = stampOf(line)
                        when {
                            line.startsWith("read: unexpected EOF") || line.startsWith("--------- beginning of") -> {}
                            // Written to the file directly already (event).
                            line.contains(" $EVENT_TAG: ") -> stamp?.let { lastStamp = it }
                            // The restart repeats lines up to its start time.
                            stamp != null && copiedUntil != null && stamp <= copiedUntil -> {}
                            else -> {
                                append(line)
                                if (stamp != null) lastStamp = stamp
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "logcat copy stopped", e)
            }
            restarts++
            // A stream that keeps dying at once is retried more slowly.
            delayMs = if (System.currentTimeMillis() - startedAt < 10_000) (delayMs * 2).coerceAtMost(30_000) else 2_000
            append("${stamp()} ---- log stream restarted ($restarts) ----")
            Thread.sleep(delayMs)
        }
    }

    @Volatile private var restarts = 0
    private const val EVENT_TAG = "TurboTextEvent"

    /** "MM-dd HH:mm:ss.SSS" at the start of a threadtime line, or null.
     *  Compares correctly as text within a year. */
    internal fun stampOf(line: String): String? =
        if (line.length >= 18 && line[2] == '-' && line[5] == ' ' && line[8] == ':' && line[14] == '.') line.substring(0, 18) else null

    /**
     * Something worth keeping even if the log copy breaks (a send and its
     * result): written straight to the file, and to logcat as usual.
     */
    fun event(tag: String, text: String) {
        Log.i(EVENT_TAG, "$tag: $text")
        append("${stamp()} $EVENT_TAG $tag: $text")
    }

    private fun append(line: String) {
        val logs = dir ?: return
        synchronized(lock) {
            try {
                val file = File(logs, "app.log")
                if (file.length() > MAX_BYTES) {
                    val old = File(logs, "app.log.1")
                    old.delete()
                    file.renameTo(old)
                }
                FileWriter(File(logs, "app.log"), true).use { it.write(line + "\n") }
            } catch (e: Exception) {
                // Nowhere to report it; logging must never break the app.
            }
        }
    }

    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }
                append("${stamp()} CRASH in thread ${thread.name}:\n$trace")
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun stamp() = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date())

    /** The phone and app details a bug report starts with. */
    private fun header(context: Context): String {
        fun granted(p: String) =
            if (androidx.core.content.ContextCompat.checkSelfPermission(context, p) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) "yes" else "NO"
        return buildString {
            appendLine("TurboText bug report, ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())}")
            appendLine("App: $version")
            appendLine("Phone: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Default texting app: ${if (DefaultSmsRoleHelper.isDefault(context)) "yes" else "NO"}")
            appendLine("Background Helper: ${if (AccessibilityHelper.isServiceEnabled(context)) "on" else "off"}")
            appendLine("Log: stream restarted $restarts time(s) since TurboText started")
            appendLine("In service: ${if (SendRetry.inService(context)) "yes" else "NO"}; waiting to send: ${SendRetry.waitingCount(context)}")
            for (p in listOf(
                "READ_SMS", "SEND_SMS", "RECEIVE_SMS", "RECEIVE_MMS", "RECEIVE_WAP_PUSH",
                "READ_CONTACTS", "READ_PHONE_STATE", "RECORD_AUDIO"
            )) appendLine("Permission $p: ${granted("android.permission.$p")}")
            appendLine()
        }
    }

    /**
     * Saves a bug report (the header, then the log, oldest first) to the
     * phone's Download folder. Returns where it went, or null on failure.
     * Off the main thread.
     */
    fun saveReport(context: Context): String? {
        val name = "TurboText-bug-report-${SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())}.txt"
        val text = buildString {
            append(header(context))
            synchronized(lock) {
                dir?.let { logs ->
                    listOf(File(logs, "app.log.1"), File(logs, "app.log"))
                        .filter { it.exists() }
                        .forEach { append(it.readText()) }
                }
            }
        }
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: return null
                context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) } ?: return null
            } else {
                @Suppress("DEPRECATION")
                val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                downloads.mkdirs()
                File(downloads, name).writeText(text)
            }
            "Download/$name"
        } catch (e: Exception) {
            Log.w(TAG, "couldn't save the bug report", e)
            null
        }
    }
}
