package com.turbotext.app

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class AppShortcutActivity : AppCompatActivity() {

    private lateinit var chosenAppRow: TextView
    private lateinit var launchNowRow: TextView
    private var currentRow = 0
    private val lastRow = 1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_shortcut)

        ThemeHelper.apply(this)
        chosenAppRow = findViewById(R.id.chosenAppRow)
        launchNowRow = findViewById(R.id.launchNowRow)

        chosenAppRow.setOnClickListener { openPicker() }
        launchNowRow.setOnClickListener { launchChosenApp() }

        updateRowHighlight()
    }

    override fun onResume() {
        super.onResume()
        val label = AppShortcutHelper.getChosenLabel(this)
        chosenAppRow.text = "Choose App: ${label ?: "Not Set"}"
    }

    private fun openPicker() {
        startActivity(Intent(this, AppPickerActivity::class.java))
    }

    private fun launchChosenApp() {
        val pkg = AppShortcutHelper.getChosenPackage(this)
        if (pkg == null) {
            Toast.makeText(this, "Choose an app first", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = packageManager.getLaunchIntentForPackage(pkg)
        if (intent != null) {
            startActivity(intent)
        } else {
            Toast.makeText(this, "Couldn't launch that app — it may not have its own screen to open (some utility apps run in the background only)", Toast.LENGTH_LONG).show()
        }
    }

    private fun updateRowHighlight() {
        chosenAppRow.setBackgroundColor(if (currentRow == 0) ThemeHelper.getCurrentTheme(this).surface2 else android.graphics.Color.TRANSPARENT)
        launchNowRow.setBackgroundColor(if (currentRow == 1) ThemeHelper.getCurrentTheme(this).surface2 else android.graphics.Color.TRANSPARENT)
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
                if (currentRow == 0) openPicker() else launchChosenApp()
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }
}
