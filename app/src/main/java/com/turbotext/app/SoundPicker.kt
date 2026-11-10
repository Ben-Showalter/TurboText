package com.turbotext.app

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.widget.AdapterView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog

/**
 * Picking a notification sound: first where from (the phone's own, the
 * built-in ones, your own files), then the sounds there. Each sound plays
 * as the highlight reaches it; OK picks it, and the list stays open to
 * try others until Back closes it. Used by Settings > Notifications and
 * by a conversation's Contact Options.
 */
object SoundPicker {
    private val handler = Handler(Looper.getMainLooper())

    /**
     * [noneLabel] is the first choice, which picks no sound (null): "No
     * Sound" in Settings, "Use default sound" for one conversation.
     * [beforeMyFiles] runs before your own folder is listed; it returns
     * true when it had to ask for access first (then nothing is shown).
     */
    fun show(
        activity: Activity,
        title: String,
        current: String?,
        noneLabel: String,
        beforeMyFiles: () -> Boolean = { false },
        onPicked: (String?) -> Unit
    ) {
        val sources = NotificationSounds.Source.values()
        val counts = sources.map { NotificationSounds.all(activity, it).size }
        val options = listOf(noneLabel) + sources.mapIndexed { i, src -> "${src.label} (${counts[i]})" }
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setItems(options.toTypedArray()) { _, which ->
                if (which == 0) {
                    onPicked(null)
                } else {
                    val source = sources[which - 1]
                    if (source == NotificationSounds.Source.MY_FILES && beforeMyFiles()) return@setItems
                    list(activity, source, current, onPicked)
                }
            }
            .show()
    }

    private fun list(activity: Activity, source: NotificationSounds.Source, current: String?, onPicked: (String?) -> Unit) {
        val sounds = NotificationSounds.all(activity, source)
        if (sounds.isEmpty()) {
            Toast.makeText(activity, "No sounds there", Toast.LENGTH_SHORT).show()
            return
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle(source.label)
            .setSingleChoiceItems(sounds.map { it.name }.toTypedArray(), sounds.indexOfFirst { it.ref == current }) { _, which ->
                val chosen = sounds[which]
                onPicked(chosen.ref)
                SoundNotificationHelper.preview(activity, chosen.ref)
            }
            .setOnDismissListener {
                handler.removeCallbacksAndMessages(null)
                SoundNotificationHelper.stopPreview()
            }
            .create()
        // Play each sound as the D-pad highlight reaches it, after a short
        // pause so holding Down doesn't start every sound it passes.
        dialog.listView.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                val sound = sounds.getOrNull(position) ?: return
                handler.removeCallbacksAndMessages(null)
                handler.postDelayed({ SoundNotificationHelper.preview(activity, sound.ref) }, 250)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        dialog.show()
    }
}
