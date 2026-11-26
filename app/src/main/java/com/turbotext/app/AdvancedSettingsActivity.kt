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
    private lateinit var backgroundHelperRow: TextView
    private lateinit var trustedNumberRow: TextView
    private lateinit var mmsReportsRow: TextView
    private lateinit var reactionsRow: TextView
    private lateinit var messagesShownRow: TextView
    private lateinit var clearLearningRow: TextView
    private lateinit var bugReportRow: TextView
    private lateinit var checkUpdatesRow: TextView
    private var currentRow = 0
    private val rows get() = listOf(defaultAppRow, backgroundHelperRow, trustedNumberRow, mmsReportsRow, reactionsRow, messagesShownRow, clearLearningRow, bugReportRow, checkUpdatesRow)
    private val lastRow get() = rows.size - 1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_advanced_settings)
        ThemeHelper.apply(this)

        defaultAppRow = findViewById(R.id.defaultAppRow)
        backgroundHelperRow = findViewById(R.id.backgroundHelperRow)
        trustedNumberRow = findViewById(R.id.trustedNumberRow)
        mmsReportsRow = findViewById(R.id.mmsReportsRow)
        reactionsRow = findViewById(R.id.reactionsRow)
        messagesShownRow = findViewById(R.id.messagesShownRow)
        clearLearningRow = findViewById(R.id.clearLearningRow)
        bugReportRow = findViewById(R.id.bugReportRow)
        checkUpdatesRow = findViewById(R.id.checkUpdatesRow)

        defaultAppRow.setOnClickListener { openDefaultAppSettings() }
        backgroundHelperRow.setOnClickListener { AccessibilityHelper.openSettings(this) }
        trustedNumberRow.setOnClickListener { promptTrustedNumber() }
        mmsReportsRow.setOnClickListener { toggleMmsReports() }
        updateMmsReportsRowLabel()
        reactionsRow.setOnClickListener { toggleReactions() }
        updateReactionsRowLabel()
        messagesShownRow.setOnClickListener { cycleMessagesShown() }
        clearLearningRow.setOnClickListener { confirmClearLearning() }
        updateMessagesShownRowLabel()
        bugReportRow.setOnClickListener { saveBugReport() }
        checkUpdatesRow.setOnClickListener { checkForUpdate() }
        checkUpdatesRow.text = "Check for Updates (installed v${UpdateChecker.installedVersion(this)})"

        updateTrustedNumberRowLabel()
        updateRowHighlight()
    }

    override fun onResume() {
        super.onResume()
        updateTrustedNumberRowLabel()
        // Back from Accessibility settings: show the new state, and swap
        // the "running" notification in or out to match.
        backgroundHelperRow.text =
            "Background Helper: ${if (AccessibilityHelper.isServiceEnabled(this)) "On" else "Off"}"
        BackgroundHost.ensureRunning(this)
    }

    private fun updateMmsReportsRowLabel() {
        mmsReportsRow.text = "Picture Delivery Reports: ${if (SettingsHelper.isMmsDeliveryReports(this)) "On" else "Off"}"
    }

    /** Whether picture messages ask for a "Delivered" report. */
    private fun toggleMmsReports() {
        SettingsHelper.setMmsDeliveryReports(this, !SettingsHelper.isMmsDeliveryReports(this))
        updateMmsReportsRowLabel()
    }

    /** T9 Word forgets which words you use most (My Words and contacts
     *  stay), after a confirmation. */
    private fun confirmClearLearning() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Clear Learned Words?")
            .setMessage("T9 Word forgets which words you use most. Your own words and contacts stay.")
            .setPositiveButton("Clear") { _, _ ->
                T9EngineHolder.get(this).clearLearning()
                android.widget.Toast.makeText(this, "Learned words cleared", android.widget.Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun updateMessagesShownRowLabel() {
        messagesShownRow.text = "Messages Shown: ${ThreadWindow.label(SettingsHelper.getMessagesShown(this))}"
    }

    /** How many of a conversation's newest messages it shows: 100, 200,
     *  500 or All. Fewer keeps long threads quick; Load More shows older. */
    private fun cycleMessagesShown() {
        SettingsHelper.setMessagesShown(this, ThreadWindow.next(SettingsHelper.getMessagesShown(this)))
        updateMessagesShownRowLabel()
        MessageCache.clear()
    }

    private fun updateReactionsRowLabel() {
        reactionsRow.text = "Show Reactions: ${if (SettingsHelper.isShowReactions(this)) "On" else "Off"}"
    }

    /** Reactions on messages, or every reaction as its original text. */
    private fun toggleReactions() {
        SettingsHelper.setShowReactions(this, !SettingsHelper.isShowReactions(this))
        updateReactionsRowLabel()
        MessageCache.clear()
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

    /** Manual counterpart to the conversation list's weekly automatic
     *  check (MainActivity.maybeCheckForUpdate). */
    private fun checkForUpdate() {
        Toast.makeText(this, "Checking for updates…", Toast.LENGTH_SHORT).show()
        UpdatePrompt.fetch(this) { result ->
            val release = result.getOrNull()
            when {
                result.isFailure ->
                    Toast.makeText(this, "Couldn't check for updates", Toast.LENGTH_LONG).show()
                release == null ->
                    Toast.makeText(this, "TurboText is up to date", Toast.LENGTH_SHORT).show()
                else -> UpdatePrompt.show(this, release)
            }
        }
    }

    private fun updateRowHighlight() {
        val surface2 = ThemeHelper.getCurrentTheme(this).surface2
        val rows = rows
        rows.forEachIndexed { index, row ->
            row.setBackgroundColor(if (currentRow == index) surface2 else android.graphics.Color.TRANSPARENT)
        }
        rows[currentRow].requestRectangleOnScreen(
            android.graphics.Rect(0, 0, rows[currentRow].width, rows[currentRow].height), true
        )
    }

    /** Saves TurboText's log (AppLog) to Download, to hand to whoever
     *  is fixing a problem. */
    private fun saveBugReport() {
        Toast.makeText(this, "Saving bug report…", Toast.LENGTH_SHORT).show()
        Thread {
            val where = AppLog.saveReport(applicationContext)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                AlertDialog.Builder(this)
                    .setTitle("Bug Report")
                    .setMessage(
                        if (where != null) "Saved to $where.\n\nCopy it to a computer over USB and send it to whoever is " +
                            "helping you. It lists phone numbers and contact names, but not what your messages say."
                        else "Couldn't save the bug report."
                    )
                    .setPositiveButton("OK", null)
                    .show()
            }
        }.start()
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
                    1 -> AccessibilityHelper.openSettings(this)
                    2 -> promptTrustedNumber()
                    3 -> toggleMmsReports()
                    4 -> toggleReactions()
                    5 -> cycleMessagesShown()
                    6 -> confirmClearLearning()
                    7 -> saveBugReport()
                    8 -> checkForUpdate()
                }
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }
}
