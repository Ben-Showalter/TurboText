package com.turbotext.app

import android.os.Bundle
import android.view.KeyEvent
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.io.File

class NotificationSettingsActivity : AppCompatActivity() {

    private lateinit var notificationSoundRow: TextView
    private lateinit var vibrateRow: TextView
    private lateinit var notificationRepeatRow: TextView
    private lateinit var readAloudRow: TextView
    private lateinit var nativeVoiceRow: TextView
    private lateinit var speechRateRow: TextView
    private var currentRow = 0
    private val lastRow = 5

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_notification_settings)
        ThemeHelper.apply(this)

        notificationSoundRow = findViewById(R.id.notificationSoundRow)
        vibrateRow = findViewById(R.id.vibrateRow)
        notificationRepeatRow = findViewById(R.id.notificationRepeatRow)
        readAloudRow = findViewById(R.id.readAloudRow)
        nativeVoiceRow = findViewById(R.id.nativeVoiceRow)
        speechRateRow = findViewById(R.id.speechRateRow)

        notificationSoundRow.setOnClickListener { showSoundPicker() }
        vibrateRow.setOnClickListener { showVibratePicker() }
        notificationRepeatRow.setOnClickListener { showRepeatPicker() }
        readAloudRow.setOnClickListener { showReadAloudPicker() }
        nativeVoiceRow.setOnClickListener { showNativeVoicePicker() }
        speechRateRow.setOnClickListener { showSpeechRatePicker() }

        updateSoundRowLabel()
        updateVibrateRowLabel()
        updateRepeatRowLabel()
        updateReadAloudRowLabel()
        updateNativeVoiceRowLabel()
        updateSpeechRateRowLabel()
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
            "Notification Sound: ${File(path).nameWithoutExtension}"
        }
    }

    private fun showSoundPicker() {
        if (promptForAllFilesAccess()) return
        val sounds = SoundNotificationHelper.listAvailableSounds()
        val options = mutableListOf("No Sound")
        options.addAll(sounds.map { it.nameWithoutExtension })
        AlertDialog.Builder(this)
            .setTitle("Notification Sound")
            .setItems(options.toTypedArray()) { _, which ->
                if (which == 0) {
                    SettingsHelper.setNotificationSoundPath(this, null)
                } else {
                    val chosen = sounds[which - 1]
                    SettingsHelper.setNotificationSoundPath(this, chosen.absolutePath)
                    SoundNotificationHelper.playSound(this, chosen.absolutePath)
                }
                updateSoundRowLabel()
            }
            .show()
    }

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

    private fun updateReadAloudRowLabel() {
        val label = when (SettingsHelper.getReadAloudMode(this)) {
            "always" -> "Always"
            "bluetooth" -> "Only on Bluetooth"
            else -> "Never"
        }
        readAloudRow.text = "Read Aloud: $label"
    }

    private fun showReadAloudPicker() {
        val options = arrayOf("Never", "Always", "Only on Bluetooth")
        val values = arrayOf("never", "always", "bluetooth")
        AlertDialog.Builder(this)
            .setTitle("Read Aloud")
            .setItems(options) { _, which ->
                SettingsHelper.setReadAloudMode(this, values[which])
                updateReadAloudRowLabel()
            }
            .show()
    }

    private fun updateNativeVoiceRowLabel() {
        val name = SettingsHelper.getNativeVoiceName(this)
        nativeVoiceRow.text = "Voice: ${name ?: "Default"}"
    }

    private fun showNativeVoicePicker() {
        val toast = Toast.makeText(this, "Checking available voices…", Toast.LENGTH_SHORT)
        toast.show()
        NativeTtsHelper.listVoicesWithStatus(this) { voices ->
            toast.cancel()
            if (voices.isEmpty()) {
                Toast.makeText(
                    this,
                    "This device's TTS engine doesn't offer alternate voices — only the default is available",
                    Toast.LENGTH_LONG
                ).show()
                return@listVoicesWithStatus
            }
            val labels = voices.map { v ->
                val quality = when {
                    v.quality >= android.speech.tts.Voice.QUALITY_VERY_HIGH -> "Very High"
                    v.quality >= android.speech.tts.Voice.QUALITY_HIGH -> "High"
                    v.quality >= android.speech.tts.Voice.QUALITY_NORMAL -> "Normal"
                    v.quality >= android.speech.tts.Voice.QUALITY_LOW -> "Low"
                    else -> "Very Low"
                }
                "${v.name} ($quality)"
            }.toTypedArray()
            AlertDialog.Builder(this)
                .setTitle("Voice (${voices.size} available)")
                .setItems(labels) { _, which ->
                    val voice = voices[which]
                    NativeTtsHelper.setVoice(this, voice.name)
                    updateNativeVoiceRowLabel()
                    NativeTtsHelper.speak(this, "This is the selected voice.")
                }
                .show()
        }
    }

    private fun updateSpeechRateRowLabel() {
        val label = when (SettingsHelper.getNativeSpeechRate(this)) {
            0.75f -> "Slower"
            1.25f -> "Faster"
            else -> "Normal"
        }
        speechRateRow.text = "Speech Rate: $label"
    }

    private fun showSpeechRatePicker() {
        val options = arrayOf("Slower", "Normal", "Faster")
        val values = arrayOf(0.75f, 1.0f, 1.25f)
        AlertDialog.Builder(this)
            .setTitle("Speech Rate")
            .setItems(options) { _, which ->
                NativeTtsHelper.setSpeechRate(this, values[which])
                updateSpeechRateRowLabel()
                NativeTtsHelper.speak(this, "This is the speech rate.")
            }
            .show()
    }

    private fun updateRowHighlight() {
        val surface2 = ThemeHelper.getCurrentTheme(this).surface2
        val rows = listOf(notificationSoundRow, vibrateRow, notificationRepeatRow, readAloudRow, nativeVoiceRow, speechRateRow)
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
                    3 -> showReadAloudPicker()
                    4 -> showNativeVoicePicker()
                    5 -> showSpeechRatePicker()
                }
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }
}
