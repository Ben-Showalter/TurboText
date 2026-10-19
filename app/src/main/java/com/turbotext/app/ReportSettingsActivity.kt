package com.turbotext.app

import android.os.Bundle
import android.view.KeyEvent
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * Settings > Report: the daily texting report (ReportSender). Guarded by
 * a PIN — chosen the first time this opens, asked for every time after —
 * so the person whose texting it reports on can't turn it off.
 */
class ReportSettingsActivity : AppCompatActivity() {

    private lateinit var enabledRow: TextView
    private lateinit var timeRow: TextView
    private lateinit var numberRow: TextView
    private lateinit var sendNowRow: TextView
    private lateinit var changePinRow: TextView
    private var currentRow = 0
    private val lastRow = 4

    /** Nothing shows or responds until the PIN has been given. */
    private var unlocked = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_report_settings)
        ThemeHelper.apply(this)

        enabledRow = findViewById(R.id.reportEnabledRow)
        timeRow = findViewById(R.id.reportTimeRow)
        numberRow = findViewById(R.id.reportNumberRow)
        sendNowRow = findViewById(R.id.reportSendNowRow)
        changePinRow = findViewById(R.id.reportChangePinRow)
        findViewById<android.view.View>(R.id.reportRows).visibility = android.view.View.INVISIBLE

        if (ReportSettings.hasPin(this)) askPin() else choosePin(firstTime = true)
    }

    private fun askPin() {
        DigitEntryDialog(
            this, "Report PIN", "Enter the PIN, then press OK.", masked = true, maxLength = 8,
            onCancelled = { finish() }
        ) { pin ->
            if (ReportSettings.checkPin(this, pin)) {
                unlock()
            } else {
                Toast.makeText(this, "Wrong PIN", Toast.LENGTH_SHORT).show()
                finish()
            }
        }.show()
    }

    /** Asks for a new PIN twice. The first time this screen opens, backing
     *  out of it closes the screen. */
    private fun choosePin(firstTime: Boolean) {
        val cancelled = { if (firstTime) finish() }
        val title = if (firstTime) "Choose a Report PIN" else "New Report PIN"
        DigitEntryDialog(
            this, title, "4 to 8 digits, then OK. It's asked every time Report opens.",
            masked = true, maxLength = 8, onCancelled = cancelled
        ) { pin ->
            if (pin.length < 4) {
                Toast.makeText(this, "The PIN needs at least 4 digits", Toast.LENGTH_SHORT).show()
                choosePin(firstTime)
                return@DigitEntryDialog
            }
            DigitEntryDialog(
                this, "Enter It Again", "The same PIN, then OK.",
                masked = true, maxLength = 8, onCancelled = cancelled
            ) { again ->
                if (again == pin) {
                    ReportSettings.setPin(this, pin)
                    Toast.makeText(this, "PIN saved", Toast.LENGTH_SHORT).show()
                    if (firstTime) unlock()
                } else {
                    Toast.makeText(this, "The PINs didn't match — try again", Toast.LENGTH_SHORT).show()
                    choosePin(firstTime)
                }
            }.show()
        }.show()
    }

    private fun unlock() {
        unlocked = true
        findViewById<android.view.View>(R.id.reportRows).visibility = android.view.View.VISIBLE
        updateLabels()
        updateRowHighlight()
    }

    private fun updateLabels() {
        enabledRow.text = "Daily Report: ${if (ReportSettings.isEnabled(this)) "On" else "Off"}"
        timeRow.text = "Send At: ${ReportBuilder.timeLabel(ReportSettings.getHour(this), ReportSettings.getMinute(this))}"
        numberRow.text = "Send To: ${ReportSettings.getNumber(this) ?: "Not Set"}"
    }

    private fun toggleEnabled() {
        val on = !ReportSettings.isEnabled(this)
        if (on && ReportSettings.getNumber(this) == null) {
            Toast.makeText(this, "Set the number to send to first", Toast.LENGTH_SHORT).show()
            return
        }
        ReportSettings.setEnabled(this, on)
        ReportSender.schedule(this)
        updateLabels()
    }

    private fun editTime() {
        val current = ReportBuilder.timeLabel(ReportSettings.getHour(this), ReportSettings.getMinute(this)).replace(":", "")
        DigitEntryDialog(this, "Send At", "24-hour time as 4 digits, e.g. 2030 for 8:30 PM.", maxLength = 4, initial = current) { typed ->
            val time = ReportBuilder.parseTime(typed)
            if (time == null) {
                Toast.makeText(this, "That isn't a time — e.g. 2030 for 8:30 PM", Toast.LENGTH_LONG).show()
                return@DigitEntryDialog
            }
            ReportSettings.setTime(this, time.first, time.second)
            ReportSender.schedule(this)
            updateLabels()
        }.show()
    }

    private fun editNumber() {
        DigitEntryDialog(
            this, "Send To", "The phone number the report is texted to. * types +.",
            allowPlus = true, initial = ReportSettings.getNumber(this) ?: ""
        ) { typed ->
            ReportSettings.setNumber(this, typed.ifBlank { null })
            if (typed.isBlank()) ReportSettings.setEnabled(this, false)
            ReportSender.schedule(this)
            updateLabels()
        }.show()
    }

    private fun sendNow() {
        if (ReportSettings.getNumber(this) == null) {
            Toast.makeText(this, "Set the number to send to first", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, "Sending report…", Toast.LENGTH_SHORT).show()
        val app = applicationContext
        Thread {
            val error = ReportSender.sendNow(app)
            runOnUiThread {
                Toast.makeText(app, error ?: "Report sent", Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    private fun updateRowHighlight() {
        val surface2 = ThemeHelper.getCurrentTheme(this).surface2
        val rows = listOf(enabledRow, timeRow, numberRow, sendNowRow, changePinRow)
        rows.forEachIndexed { index, row ->
            row.setBackgroundColor(if (currentRow == index) surface2 else android.graphics.Color.TRANSPARENT)
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!unlocked) return super.dispatchKeyEvent(event)
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
                    0 -> toggleEnabled()
                    1 -> editTime()
                    2 -> editNumber()
                    3 -> sendNow()
                    4 -> choosePin(firstTime = false)
                }
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }
}
