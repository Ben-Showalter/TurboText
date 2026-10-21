package com.turbotext.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.appcompat.app.AlertDialog

/**
 * Picks a CSV file and lays its numbers over a broadcast list
 * (CsvContacts parses, BroadcastListHelper.importInto merges). Numbers
 * already in the list are updated, not listed twice.
 */
object CsvImport {
    private const val TAG = "TurboTextBroadcast"

    /** Largest file read, well past a few thousand rows. */
    private const val MAX_BYTES = 2 * 1024 * 1024

    fun pickIntent(): Intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = "*/*"
        putExtra(
            Intent.EXTRA_MIME_TYPES,
            arrayOf("text/csv", "text/comma-separated-values", "text/plain", "application/csv", "application/vnd.ms-excel")
        )
    }

    /** The picked file's name without its extension, for a new list's name. */
    fun displayName(activity: Activity, uri: Uri): String? = try {
        activity.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
            ?.substringBeforeLast('.')?.takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        null
    }

    /** Reads [uri] off the main thread, imports into [listId], then calls
     *  [done] on the main thread with a summary to show ([showSummary]). */
    fun run(activity: Activity, uri: Uri, listId: String, done: (String) -> Unit) {
        Thread {
            val message = try {
                val bytes = activity.contentResolver.openInputStream(uri)?.use { input ->
                    val buffer = ByteArray(MAX_BYTES + 1)
                    var total = 0
                    while (total < buffer.size) {
                        val n = input.read(buffer, total, buffer.size - total)
                        if (n < 0) break
                        total += n
                    }
                    buffer.copyOf(total)
                } ?: throw java.io.IOException("couldn't open the file")
                if (bytes.size > MAX_BYTES) {
                    "That file is too big to import."
                } else {
                    val result = CsvContacts.parse(decode(bytes))
                    // Rows without a name take the saved contact's name.
                    val named = result.contacts.map { c ->
                        if (c.name.isNotBlank()) c
                        else BroadcastContact(ContactHelper.lookupName(activity, c.number) ?: "", c.number)
                    }
                    val (added, updated) = BroadcastListHelper.importInto(activity, listId, named)
                    summary(named.size, added, updated, result.invalid, result.duplicates)
                }
            } catch (e: Exception) {
                Log.w(TAG, "CSV import failed", e)
                "Couldn't read that file."
            }
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                done(message)
            }
        }.start()
    }

    fun showSummary(activity: Activity, message: String) {
        AlertDialog.Builder(activity)
            .setTitle("Import")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    /** UTF-8, or Windows-1252 when it isn't valid UTF-8 (Excel's own
     *  "CSV" on Windows), so accented names come through. */
    private fun decode(bytes: ByteArray): String {
        val utf8 = String(bytes, Charsets.UTF_8)
        return if ('\uFFFD' in utf8) String(bytes, charset("windows-1252")) else utf8
    }

    fun summary(total: Int, added: Int, updated: Int, invalid: Int, duplicates: Int): String {
        if (total == 0) return "No phone numbers found in that file." +
            if (invalid > 0) " $invalid row(s) had no valid number." else ""
        val lines = mutableListOf("Imported $total: $added new, $updated updated.")
        if (invalid > 0) lines.add("Skipped $invalid row(s) without a valid number.")
        if (duplicates > 0) lines.add("$duplicates repeated number(s) in the file counted once.")
        return lines.joinToString("\n")
    }
}
