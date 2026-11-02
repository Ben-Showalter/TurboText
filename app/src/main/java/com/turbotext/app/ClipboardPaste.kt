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
        val clip = try {
            activity.getSystemService(ClipboardManager::class.java)?.primaryClip
        } catch (e: Exception) {
            Log.w(TAG, "clipboard read refused", e)
            null
        }
        // A link or styled text comes through as plain text.
        val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(activity)?.toString().orEmpty()
        Log.i(TAG, "paste: ${if (clip == null) "no clip (or not allowed)" else "${text.length} chars"}")
        if (text.isEmpty()) {
            Toast.makeText(activity, "Clipboard is empty", Toast.LENGTH_SHORT).show()
            return
        }
        onText(text)
    }
}
