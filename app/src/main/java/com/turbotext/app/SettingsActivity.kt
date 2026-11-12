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
    private lateinit var reportRow: TextView
    private lateinit var archiveRow: TextView
    private lateinit var advancedRow: TextView
    private var currentRow = 0
    private val lastRow = 6

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        ThemeHelper.apply(this)

        notificationSettingsRow = findViewById(R.id.notificationSettingsRow)
        signatureSettingsRow = findViewById(R.id.signatureSettingsRow)
        addWordRow = findViewById(R.id.addWordRow)
        themeRow = findViewById(R.id.themeRow)
        reportRow = findViewById(R.id.reportRow)
        archiveRow = findViewById(R.id.archiveRow)
        advancedRow = findViewById(R.id.advancedRow)
        archiveRow.setOnClickListener { showArchiveOptions() }

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
        reportRow.setOnClickListener {
            startActivity(Intent(this, ReportSettingsActivity::class.java))
        }
        advancedRow.setOnClickListener {
            startActivity(Intent(this, AdvancedSettingsActivity::class.java))
        }

        updateThemeRowLabel()
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

    /** Dark, Light, and the conversation wallpaper on or off. */
    private fun showThemePicker() {
        val themes = AppTheme.values()
        val wallpaperOn = SettingsHelper.isConversationWallpaper(this)
        val options = themes.map { it.label } + "Conversation Wallpaper: ${if (wallpaperOn) "On" else "Off"}"
        AlertDialog.Builder(this)
            .setTitle("Theme")
            .setItems(options.toTypedArray()) { _, which ->
                if (which < themes.size) {
                    ThemeHelper.setTheme(this, themes[which])
                    updateThemeRowLabel()
                    ThemeHelper.apply(this)
                    updateRowHighlight()
                } else {
                    SettingsHelper.setConversationWallpaper(this, !wallpaperOn)
                    android.widget.Toast.makeText(
                        this, "Conversation wallpaper ${if (wallpaperOn) "off" else "on"}", android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }
            .show()
    }

    private fun updateRowHighlight() {
        val surface2 = ThemeHelper.getCurrentTheme(this).surface2
        val rows = listOf(notificationSettingsRow, signatureSettingsRow, addWordRow, themeRow, reportRow, archiveRow, advancedRow)
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
                    4 -> startActivity(Intent(this, ReportSettingsActivity::class.java))
                    5 -> showArchiveOptions()
                    6 -> startActivity(Intent(this, AdvancedSettingsActivity::class.java))
                }
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    // ---- Archive Options ----

    /** Settings > Archive Options: save every conversation, or put an
     *  archive back (ArchiveManager). */
    private fun showArchiveOptions() {
        val options = arrayOf("Archive All Conversations", "Import Archived Conversations")
        AlertDialog.Builder(this)
            .setTitle("Archive Options")
            .setItems(options) { _, which ->
                if (!hasStorageAccess()) return@setItems
                if (which == 0) archiveAll() else chooseArchive()
            }
            .show()
    }

    /** Archives live in the phone's Documents folder: Android 11 and later
     *  need "All files access", earlier versions the storage permission.
     *  Asks for it and returns false when it's missing. */
    private fun hasStorageAccess(): Boolean {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            if (android.os.Environment.isExternalStorageManager()) return true
            AlertDialog.Builder(this)
                .setTitle("Storage Access Needed")
                .setMessage("Archives are saved in the phone's Documents folder. Turn on \"All files access\" for TurboText on the next screen, then come back.")
                .setPositiveButton("Open Settings") { _, _ ->
                    try {
                        startActivity(Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, android.net.Uri.parse("package:$packageName")))
                    } catch (e: Exception) {
                        startActivity(Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
            return false
        }
        val needed = arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE, android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
        val missing = needed.filter {
            androidx.core.content.ContextCompat.checkSelfPermission(this, it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) return true
        androidx.core.app.ActivityCompat.requestPermissions(this, missing.toTypedArray(), 640)
        android.widget.Toast.makeText(this, "Allow storage, then choose it again", android.widget.Toast.LENGTH_LONG).show()
        return false
    }

    /** A dialog showing "<verb>… 1,200 of 4,210" while [work] runs in the
     *  background; [work] gets the progress callback and returns the text
     *  to show when it's done. */
    private fun runWithProgress(verb: String, work: ((Int, Int) -> Unit) -> String) {
        val dialog = AlertDialog.Builder(this)
            .setTitle(verb)
            .setMessage("Starting…")
            .setCancelable(false)
            .show()
        Thread {
            val message = try {
                work { done, total ->
                    runOnUiThread { dialog.setMessage("%,d of %,d messages".format(done, total)) }
                }
            } catch (e: Exception) {
                android.util.Log.w("TurboTextArchive", "$verb failed", e)
                AppLog.event("Archive", "$verb failed: ${e.javaClass.simpleName}: ${e.message}")
                "Something went wrong: ${e.message}"
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                dialog.dismiss()
                AlertDialog.Builder(this).setTitle(verb).setMessage(message).setPositiveButton("OK", null).show()
            }
        }.start()
    }

    private fun archiveAll() {
        runWithProgress("Archiving") { progress ->
            val r = ArchiveManager.archive(applicationContext, progress)
            "Archived %,d messages (%,d pictures and files) to Documents/Archived conversations/%s.\n\nOn a PC, open \"Open me.html\" in that folder."
                .format(r.messages, r.files, r.dir.name)
        }
    }

    private fun chooseArchive() {
        val archives = ArchiveManager.listArchives()
        if (archives.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("Import")
                .setMessage("No archives to import in Documents/Archived conversations. Archives made before this version can't be imported.")
                .setPositiveButton("OK", null)
                .show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Import which archive?")
            .setItems(archives.map { it.label }.toTypedArray()) { _, which ->
                val chosen = archives[which]
                runWithProgress("Importing") { progress ->
                    val r = ArchiveManager.import(applicationContext, chosen.dir, progress)
                    buildString {
                        append("Imported %,d messages.".format(r.imported))
                        if (r.skipped > 0) append("\n%,d were already on the phone.".format(r.skipped))
                        if (r.failed > 0) append("\n%,d couldn't be imported.".format(r.failed))
                    }
                }
            }
            .show()
    }
}
