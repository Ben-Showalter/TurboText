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
    private lateinit var avatarsRow: TextView
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
        avatarsRow = findViewById(R.id.avatarsRow)
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
        avatarsRow.setOnClickListener { toggleAvatars() }
        advancedRow.setOnClickListener {
            startActivity(Intent(this, AdvancedSettingsActivity::class.java))
        }

        updateThemeRowLabel()
        updateAvatarsRowLabel()
        updateRowHighlight()
    }

    override fun onResume() {
        super.onResume()
        updateThemeRowLabel()
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

    private fun updateAvatarsRowLabel() {
        avatarsRow.text = "Show Avatars: ${if (SettingsHelper.isShowAvatars(this)) "On" else "Off"}"
    }

    private fun toggleAvatars() {
        SettingsHelper.setShowAvatars(this, !SettingsHelper.isShowAvatars(this))
        updateAvatarsRowLabel()
    }

    private fun updateRowHighlight() {
        val surface2 = ThemeHelper.getCurrentTheme(this).surface2
        val rows = listOf(notificationSettingsRow, signatureSettingsRow, addWordRow, themeRow, avatarsRow, advancedRow)
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
                    4 -> toggleAvatars()
                    5 -> startActivity(Intent(this, AdvancedSettingsActivity::class.java))
                }
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }
}
