package com.turbotext.app

import android.os.Bundle
import android.view.KeyEvent
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.io.File

class NotificationSettingsActivity : AppCompatActivity() {

    private lateinit var notificationSoundRow: TextView
    private lateinit var vibrateRow: TextView
    private lateinit var notificationRepeatRow: TextView
    private lateinit var hideOnCoverRow: TextView
    private var currentRow = 0
    private val lastRow = 3

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_notification_settings)
        ThemeHelper.apply(this)

        notificationSoundRow = findViewById(R.id.notificationSoundRow)
        vibrateRow = findViewById(R.id.vibrateRow)
        notificationRepeatRow = findViewById(R.id.notificationRepeatRow)
        hideOnCoverRow = findViewById(R.id.hideOnCoverRow)

        notificationSoundRow.setOnClickListener { showSoundPicker() }
        vibrateRow.setOnClickListener { showVibratePicker() }
        notificationRepeatRow.setOnClickListener { showRepeatPicker() }
        hideOnCoverRow.setOnClickListener { toggleHideOnCover() }

        updateSoundRowLabel()
        updateVibrateRowLabel()
        updateRepeatRowLabel()
        updateHideOnCoverRowLabel()
        updateRowHighlight()
    }

    override fun onResume() {
        super.onResume()
        // Catches the case where the user just came back from granting
        // "All files access" in system Settings — the default sound files
        // never got written at app startup without it, so seed them now
        // and refresh the label instead of waiting for a full app restart.
        if (hasAllFilesAccess()) {
            Thread {
                DefaultNotificationSounds.ensureDefaultsExist(SoundNotificationHelper.soundsDirectory())
            }.start()
        }
        updateSoundRowLabel()
    }

    private fun hasAllFilesAccess(): Boolean =
        android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R || android.os.Environment.isExternalStorageManager()

    /** The notifications sound folder lives outside this app's own sandboxed
     *  storage (see the manifest comment on MANAGE_EXTERNAL_STORAGE) — on
     *  Android 11+ that write silently fails without this specific, separately
     *  granted permission, which is why sound files can appear to just never
     *  show up in that folder no matter how many times the app runs. */
    private fun promptForAllFilesAccess(): Boolean {
        if (hasAllFilesAccess()) return false
        AlertDialog.Builder(this)
            .setTitle("Storage Access Needed")
            .setMessage("TurboText needs \"All files access\" to read and write notification sound files in Internal Storage/notifications. The next screen is that permission toggle for TurboText — turn it on, then come back here.")
            .setPositiveButton("Open Settings") { _, _ ->
                try {
                    startActivity(
                        android.content.Intent(
                            android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            android.net.Uri.parse("package:$packageName")
                        )
                    )
                } catch (e: Exception) {
                    startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
        return true
    }

    private fun updateSoundRowLabel() {
        val path = SettingsHelper.getNotificationSoundPath(this)
        notificationSoundRow.text = if (path == null) {
            "Notification Sound: No Sound"
        } else {
            "Notification Sound: ${NotificationSounds.nameOf(this, path)}"
        }
    }

    /** First where from: the phone's own sounds, TurboText's built-in
     *  ones, or your own files — then the sounds there (soundList). */
    private fun showSoundPicker() {
        val sources = NotificationSounds.Source.values()
        val counts = sources.map { NotificationSounds.all(this, it).size }
        val options = listOf("No Sound") + sources.mapIndexed { i, src -> "${src.label} (${counts[i]})" }
        AlertDialog.Builder(this)
            .setTitle("Notification Sound")
            .setItems(options.toTypedArray()) { _, which ->
                if (which == 0) {
                    SettingsHelper.setNotificationSoundPath(this, null)
                    updateSoundRowLabel()
                } else {
                    val source = sources[which - 1]
                    // Only your own folder needs "All files access".
                    if (source == NotificationSounds.Source.MY_FILES && promptForAllFilesAccess()) return@setItems
                    soundList(source)
                }
            }
            .show()
    }

    /** Every sound from [source]. Each one plays as the highlight moves
     *  onto it while scrolling; picking one (OK) makes it the sound. The
     *  list stays open to try others; Back (Clear) closes it, keeping
     *  the last one picked. */
    private fun soundList(source: NotificationSounds.Source) {
        val sounds = NotificationSounds.all(this, source)
        if (sounds.isEmpty()) {
            android.widget.Toast.makeText(this, "No sounds there", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val current = SettingsHelper.getNotificationSoundPath(this)
        val dialog = AlertDialog.Builder(this)
            .setTitle(source.label)
            .setSingleChoiceItems(sounds.map { it.name }.toTypedArray(), sounds.indexOfFirst { it.ref == current }) { _, which ->
                val chosen = sounds[which]
                SettingsHelper.setNotificationSoundPath(this, chosen.ref)
                SoundNotificationHelper.preview(this, chosen.ref)
                updateSoundRowLabel()
            }
            .setOnDismissListener { previewHandler.removeCallbacksAndMessages(null); SoundNotificationHelper.stopPreview() }
            .create()
        // Play each sound as the D-pad highlight reaches it, after a short
        // pause so holding Down doesn't start every sound it passes.
        dialog.listView.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                val sound = sounds.getOrNull(position) ?: return
                previewHandler.removeCallbacksAndMessages(null)
                previewHandler.postDelayed({ SoundNotificationHelper.preview(this@NotificationSettingsActivity, sound.ref) }, 250)
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
        dialog.show()
    }

    private val previewHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private fun updateVibrateRowLabel() {
        val patternId = SettingsHelper.getVibratePattern(this)
        val label = VibrationPatterns.byId(patternId)?.label ?: "Off"
        vibrateRow.text = "Vibrate: $label"
    }

    /** Works the same way as the sound picker — "Off" plus every named
     *  pattern, and picking one plays it immediately so you know what
     *  you're choosing without needing a message to actually arrive. */
    private fun showVibratePicker() {
        val options = mutableListOf("Off")
        options.addAll(VibrationPatterns.all.map { it.label })
        AlertDialog.Builder(this)
            .setTitle("Vibrate")
            .setItems(options.toTypedArray()) { _, which ->
                if (which == 0) {
                    SettingsHelper.setVibratePattern(this, null)
                } else {
                    val chosen = VibrationPatterns.all[which - 1]
                    SettingsHelper.setVibratePattern(this, chosen.id)
                    VibrationPatterns.play(this, chosen.id)
                }
                updateVibrateRowLabel()
            }
            .show()
    }

    private fun updateRepeatRowLabel() {
        val minutes = SettingsHelper.getRepeatMinutes(this)
        notificationRepeatRow.text = if (minutes != null) {
            "Repeat: Every $minutes minute${if (minutes == 1) "" else "s"}"
        } else {
            "Repeat: Just Once"
        }
    }

    private fun showRepeatPicker() {
        val options = arrayOf("Just Once", "Every 1 minute", "Every 2 minutes", "Every 5 minutes", "Every 10 minutes", "Every 15 minutes", "Every 30 minutes", "Every 60 minutes")
        val values = arrayOf(null, 1, 2, 5, 10, 15, 30, 60)
        AlertDialog.Builder(this)
            .setTitle("Repeat")
            .setItems(options) { _, which ->
                SettingsHelper.setRepeatMinutes(this, values[which])
                updateRepeatRowLabel()
            }
            .show()
    }

    private fun updateHideOnCoverRowLabel() {
        val on = SettingsHelper.isHideMessageOnCover(this)
        hideOnCoverRow.text = "Hide Message on Cover: ${if (on) "On" else "Off"}"
    }

    private fun toggleHideOnCover() {
        SettingsHelper.setHideMessageOnCover(this, !SettingsHelper.isHideMessageOnCover(this))
        updateHideOnCoverRowLabel()
    }

    private fun updateRowHighlight() {
        val surface2 = ThemeHelper.getCurrentTheme(this).surface2
        val rows = listOf(notificationSoundRow, vibrateRow, notificationRepeatRow, hideOnCoverRow)
        for ((i, row) in rows.withIndex()) {
            row.setBackgroundColor(if (currentRow == i) surface2 else android.graphics.Color.TRANSPARENT)
        }
        rows[currentRow].requestRectangleOnScreen(
            android.graphics.Rect(0, 0, rows[currentRow].width, rows[currentRow].height), true
        )
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (currentRow > 0) {
                    currentRow--
                    updateRowHighlight()
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (currentRow < lastRow) {
                    currentRow++
                    updateRowHighlight()
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER -> {
                when (currentRow) {
                    0 -> showSoundPicker()
                    1 -> showVibratePicker()
                    2 -> showRepeatPicker()
                    3 -> toggleHideOnCover()
                }
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }
}
