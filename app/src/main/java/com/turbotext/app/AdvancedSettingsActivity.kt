package com.turbotext.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class AdvancedSettingsActivity : AppCompatActivity() {

    private lateinit var defaultAppRow: TextView
    private lateinit var appShortcutRow: TextView
    private lateinit var accessibilityRow: TextView
    private lateinit var trustedNumberRow: TextView
    private lateinit var predictionEngineRow: TextView
    private var currentRow = 0
    private val lastRow = 4

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_advanced_settings)
        ThemeHelper.apply(this)

        defaultAppRow = findViewById(R.id.defaultAppRow)
        appShortcutRow = findViewById(R.id.appShortcutRow)
        accessibilityRow = findViewById(R.id.accessibilityRow)
        trustedNumberRow = findViewById(R.id.trustedNumberRow)
        predictionEngineRow = findViewById(R.id.predictionEngineRow)

        defaultAppRow.setOnClickListener { openDefaultAppSettings() }
        appShortcutRow.setOnClickListener {
            startActivity(Intent(this, AppShortcutActivity::class.java))
        }
        accessibilityRow.setOnClickListener { openAccessibilitySettings() }
        trustedNumberRow.setOnClickListener { promptTrustedNumber() }
        predictionEngineRow.setOnClickListener { showPredictionEnginePicker() }

        updateTrustedNumberRowLabel()
        updatePredictionEngineRowLabel()
        updateRowHighlight()
    }

    override fun onResume() {
        super.onResume()
        updateTrustedNumberRowLabel()
    }

    private fun updateTrustedNumberRowLabel() {
        val number = SettingsHelper.getTrustedProvisioningNumber(this)
        trustedNumberRow.text = "Trusted Provisioning Number: ${number ?: "Not Set"}"
    }

    private fun promptTrustedNumber() {
        AlertDialog.Builder(this)
            .setTitle("Trusted Provisioning Number")
            .setMessage(
                "This is the ONLY phone number allowed to remotely activate TurboText's " +
                    "advanced features via a specially-formatted text message. Leave unset " +
                    "to disable remote provisioning entirely."
            )
            .setPositiveButton("Set Number") { _, _ ->
                val intent = Intent(this, TextEntryActivity::class.java)
                intent.putExtra(TextEntryActivity.EXTRA_TITLE, "Trusted Provisioning Number")
                intent.putExtra(TextEntryActivity.EXTRA_INITIAL_TEXT, SettingsHelper.getTrustedProvisioningNumber(this) ?: "")
                intent.putExtra(TextEntryActivity.EXTRA_ALLOW_VOICE, false)
                startActivityForResult(intent, 701)
            }
            .setNegativeButton("Clear") { _, _ ->
                SettingsHelper.setTrustedProvisioningNumber(this, null)
                updateTrustedNumberRowLabel()
            }
            .show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != Activity.RESULT_OK) return
        if (requestCode == 701) {
            val number = data?.getStringExtra(TextEntryActivity.EXTRA_RESULT_TEXT)?.trim()
            if (!number.isNullOrEmpty()) {
                SettingsHelper.setTrustedProvisioningNumber(this, number)
                updateTrustedNumberRowLabel()
                Toast.makeText(this, "Trusted number saved", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updatePredictionEngineRowLabel() {
        val label = if (SettingsHelper.getPredictionEngine(this) == T9Engine.ENGINE_CLASSIC) "Classic" else "TT9"
        predictionEngineRow.text = "T9 Prediction Engine: $label"
    }

    /** TT9 (Traditional T9's dictionary and learning) is the default; the
     *  original engine stays available in case TT9 misbehaves on a phone. */
    private fun showPredictionEnginePicker() {
        val options = arrayOf("TT9 (recommended)", "Classic")
        val values = arrayOf(T9Engine.ENGINE_TT9, T9Engine.ENGINE_CLASSIC)
        val current = values.indexOf(SettingsHelper.getPredictionEngine(this)).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("T9 Prediction Engine")
            .setSingleChoiceItems(options, current) { dialog, which ->
                SettingsHelper.setPredictionEngine(this, values[which])
                // Rebuilt in the background so the next typing screen
                // doesn't wait on the dictionary load.
                T9EngineHolder.reset()
                Thread { T9EngineHolder.get(applicationContext) }.start()
                updatePredictionEngineRowLabel()
                dialog.dismiss()
            }
            .show()
    }

    private fun updateRowHighlight() {
        val surface2 = ThemeHelper.getCurrentTheme(this).surface2
        val rows = listOf(defaultAppRow, appShortcutRow, accessibilityRow, trustedNumberRow, predictionEngineRow)
        rows.forEachIndexed { index, row ->
            row.setBackgroundColor(if (currentRow == index) surface2 else android.graphics.Color.TRANSPARENT)
        }
        rows[currentRow].requestRectangleOnScreen(
            android.graphics.Rect(0, 0, rows[currentRow].width, rows[currentRow].height), true
        )
    }

    private fun openDefaultAppSettings() {
        val intent = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            Intent(android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
        } else {
            Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS)
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Open Settings > Apps > Default apps > SMS app", Toast.LENGTH_LONG).show()
        }
    }

    /** The one-time manual step the outer-screen unread pulse
     *  (KeyButtonAccessibilityService) depends on. */
    private fun openAccessibilitySettings() = AccessibilityHelper.openSettings(this)

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
                    0 -> openDefaultAppSettings()
                    1 -> startActivity(Intent(this, AppShortcutActivity::class.java))
                    2 -> openAccessibilitySettings()
                    3 -> promptTrustedNumber()
                    4 -> showPredictionEnginePicker()
                }
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }
}
