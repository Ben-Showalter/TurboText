package com.turbotext.app

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    private lateinit var notificationSettingsRow: TextView
    private lateinit var signatureSettingsRow: TextView
    private lateinit var addWordRow: TextView
    private lateinit var themeRow: TextView
    private lateinit var micSourceRow: TextView
    private lateinit var advancedRow: TextView
    private var currentRow = 0
    private val lastRow = 5

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        ThemeHelper.apply(this)

        notificationSettingsRow = findViewById(R.id.notificationSettingsRow)
        signatureSettingsRow = findViewById(R.id.signatureSettingsRow)
        addWordRow = findViewById(R.id.addWordRow)
        themeRow = findViewById(R.id.themeRow)
        micSourceRow = findViewById(R.id.micSourceRow)
        advancedRow = findViewById(R.id.advancedRow)

        notificationSettingsRow.setOnClickListener {
            startActivity(Intent(this, NotificationSettingsActivity::class.java))
        }
        signatureSettingsRow.setOnClickListener {
            startActivity(Intent(this, SignatureSettingsActivity::class.java))
        }
        addWordRow.setOnClickListener {
            startActivity(Intent(this, MyWordsActivity::class.java))
        }
        themeRow.setOnClickListener { showThemePicker() }
        micSourceRow.setOnClickListener { showMicSourcePicker() }
        advancedRow.setOnClickListener {
            startActivity(Intent(this, AdvancedSettingsActivity::class.java))
        }

        updateThemeRowLabel()
        updateMicSourceRowLabel()
        updateRowHighlight()
    }

    override fun onResume() {
        super.onResume()
        updateThemeRowLabel()
        updateMicSourceRowLabel()
        updateRowHighlight()
    }

    private fun updateThemeRowLabel() {
        themeRow.text = "Theme: ${ThemeHelper.getCurrentTheme(this).label}"
    }

    private fun showThemePicker() {
        val themes = AppTheme.values()
        val options = themes.map { it.label }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Theme")
            .setItems(options) { _, which ->
                ThemeHelper.setTheme(this, themes[which])
                updateThemeRowLabel()
                ThemeHelper.apply(this)
                updateRowHighlight()
            }
            .show()
    }

    private fun updateMicSourceRowLabel() {
        val label = if (SettingsHelper.isBluetoothMicEnabled(this)) "Bluetooth Headset" else "Phone"
        micSourceRow.text = "Voice Input Mic: $label"
    }

    /** Voice-to-text (push-to-talk) only — the higher-fidelity voice-memo
     *  recorder for MMS attachments is unaffected by this setting. When
     *  turned on but no headset is paired/connected at record time,
     *  GroqVoiceInputHelper falls back to the phone mic automatically. */
    private fun showMicSourcePicker() {
        val options = arrayOf("Phone (default)", "Bluetooth Headset")
        val current = if (SettingsHelper.isBluetoothMicEnabled(this)) 1 else 0
        AlertDialog.Builder(this)
            .setTitle("Voice Input Mic")
            .setSingleChoiceItems(options, current) { dialog, which ->
                SettingsHelper.setBluetoothMicEnabled(this, which == 1)
                updateMicSourceRowLabel()
                dialog.dismiss()
            }
            .show()
    }

    private fun updateRowHighlight() {
        val surface2 = ThemeHelper.getCurrentTheme(this).surface2
        val rows = listOf(notificationSettingsRow, signatureSettingsRow, addWordRow, themeRow, micSourceRow, advancedRow)
        rows.forEachIndexed { index, row ->
            row.setBackgroundColor(if (currentRow == index) surface2 else android.graphics.Color.TRANSPARENT)
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
                    0 -> startActivity(Intent(this, NotificationSettingsActivity::class.java))
                    1 -> startActivity(Intent(this, SignatureSettingsActivity::class.java))
                    2 -> startActivity(Intent(this, MyWordsActivity::class.java))
                    3 -> showThemePicker()
                    4 -> showMicSourcePicker()
                    5 -> startActivity(Intent(this, AdvancedSettingsActivity::class.java))
                }
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }
}
