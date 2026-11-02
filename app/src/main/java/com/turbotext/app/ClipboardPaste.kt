package com.turbotext.app

import android.app.Activity
import android.content.ClipboardManager
import android.util.Log
import android.widget.Toast
import java.lang.ref.WeakReference

/**
 * Reads the system clipboard for Paste. Android 10 and later only let an
 * app read it while one of its own windows has focus — and Paste is
 * picked from the Options dialog, whose window is closing at that moment,
 * so the clipboard came back empty. The read now waits until the screen
 * itself has focus again (the activity calls [onWindowFocus] from
 * onWindowFocusChanged).
 */
object ClipboardPaste {
    private const val TAG = "TurboTextPaste"
    private var pending: Pair<WeakReference<Activity>, (String) -> Unit>? = null

    /** Calls [onText] with the clipboard's text, now or once [activity]
     *  has focus. */
    fun request(activity: Activity, onText: (String) -> Unit) {
        if (activity.hasWindowFocus()) {
            read(activity, onText)
        } else {
            pending = WeakReference(activity) to onText
        }
    }

    /** From the activity's onWindowFocusChanged(true). */
    fun onWindowFocus(activity: Activity) {
        val (ref, onText) = pending ?: return
        if (ref.get() !== activity) return
        pending = null
        read(activity, onText)
    }

    private fun read(activity: Activity, onText: (String) -> Unit) {
        val manager = activity.getSystemService(ClipboardManager::class.java)
        val clip = try {
            manager?.primaryClip
        } catch (e: Exception) {
            Log.w(TAG, "clipboard read refused", e)
            null
        }
        // What the clipboard holds, without its contents, for the bug
        // report: whether Android says there is something, its types, and
        // which form each item is in.
        Log.i(
            TAG,
            "paste: focus=${activity.hasWindowFocus()} hasClip=${try { manager?.hasPrimaryClip() } catch (e: Exception) { "error" }} " +
                "types=${clip?.description?.let { d -> (0 until d.mimeTypeCount).joinToString { d.getMimeType(it) } } ?: "-"} " +
                "items=${clip?.itemCount ?: 0} " +
                (clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.let { i ->
                    "text=${i.text?.length ?: "none"} html=${i.htmlText?.length ?: "none"} uri=${i.uri != null} intent=${i.intent != null}"
                } ?: "")
        )
        // Every item, in order: some editors put the selection in a later
        // one. Links and styled text come through as plain text.
        val text = clip?.let { c ->
            (0 until c.itemCount).map { c.getItemAt(it).coerceToText(activity)?.toString().orEmpty() }
                .filter { it.isNotEmpty() }.joinToString("\n")
        }.orEmpty()
        if (text.isEmpty()) {
            Toast.makeText(activity, "Clipboard is empty", Toast.LENGTH_SHORT).show()
            return
        }
        onText(text)
    }
}
