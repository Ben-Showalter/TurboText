package com.turbotext.app

import android.app.Dialog
import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.Window
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Picks a time of day from the keypad: hour (1–12), minute and AM/PM,
 * side by side. Up/Down changes the highlighted one (hold to keep
 * going), Left/Right moves between them, and the number keys can type
 * the hour or minute. OK saves; Clear or the left softkey cancels.
 *
 * With [days] (Schedule Send) a Day column comes first ("Today",
 * "Tomorrow", "Wed 4"…), starting on [day]; [describe] writes the line
 * under the selector (the full date and time), and [check] can refuse
 * a choice with a message, keeping the dialog open.
 * [onPicked] gets the day's index (0 without [days]), the 24-hour hour
 * and the minute.
 */
class KeypadTimeDialog(
    context: Context,
    title: String,
    hour24: Int,
    minute: Int,
    private val days: List<String>? = null,
    day: Int = 0,
    private val describe: ((day: Int, hour24: Int, minute: Int) -> String)? = null,
    private val check: ((day: Int, hour24: Int, minute: Int) -> String?)? = null,
    private val onPicked: (day: Int, hour24: Int, minute: Int) -> Unit
) : Dialog(context) {

    private val theme = ThemeHelper.getCurrentTheme(context)
    private var hour12 = if (hour24 % 12 == 0) 12 else hour24 % 12
    private var minute = minute
    private var pm = hour24 >= 12
    private var dayIndex = day.coerceIn(0, ((days?.size ?: 1) - 1).coerceAtLeast(0))

    /** Columns before the hour: 1 with a Day column, else 0. */
    private val offset = if (days != null) 1 else 0
    private var column = 0
    private val cells = List(3 + offset) { TextView(context) }
    private val summary = TextView(context)

    /** Digits typed into the current column so far. */
    private var typed = ""

    /** A release only counts if its press came here too: this opens on
     *  OK's press, and that OK's release must not save at once. */
    private val pressed = HashSet<Int>()

    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()

    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        val pad = dp(12)
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(theme.surface)
            setPadding(pad, pad, pad, pad)
        }
        root.addView(TextView(context).apply {
            text = title
            setTextColor(theme.textPrimary)
            textSize = 18f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, pad, 0, pad)
        }
        cells.forEachIndexed { i, cell ->
            val isDay = i < offset
            // Smaller with a Day column, so "Tomorrow 12:30 PM" fits 240px.
            cell.textSize = if (days != null) (if (isDay) 17f else 20f) else 26f
            cell.gravity = Gravity.CENTER
            cell.setTextColor(theme.textPrimary)
            cell.setPadding(dp(if (days != null) 3 else 6), dp(4), dp(if (days != null) 3 else 6), dp(4))
            row.addView(cell, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, if (isDay) 2.2f else 1f))
            if (i == offset) {
                row.addView(TextView(context).apply {
                    text = ":"
                    textSize = if (days != null) 20f else 26f
                    setTextColor(theme.textPrimary)
                })
            }
        }
        root.addView(row)
        if (describe != null) {
            root.addView(summary.apply {
                setTextColor(theme.accent)
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, dp(6))
            })
        }
        root.addView(TextView(context).apply {
            text = "▲▼ change   ◀▶ move"
            setTextColor(theme.textSecondary)
            textSize = 12f
            gravity = Gravity.CENTER
        })
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, pad, 0, 0)
        }
        fun label(text: String, gravity: Int, bold: Boolean = false) = TextView(context).apply {
            this.text = text
            this.gravity = gravity
            setTextColor(theme.accent)
            textSize = 14f
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        bar.addView(label("Cancel", Gravity.START), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(label("Save", Gravity.CENTER, bold = true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(TextView(context), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(bar)

        setContentView(root)
        window?.setBackgroundDrawable(ColorDrawable(theme.surface))
        window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        setOnKeyListener { _, keyCode, event ->
            when (event.action) {
                KeyEvent.ACTION_DOWN -> {
                    pressed.add(keyCode)
                    onKeyPressed(keyCode)
                }
                KeyEvent.ACTION_UP -> if (pressed.remove(keyCode)) onKeyReleased(keyCode)
            }
            true
        }
        render()
    }

    /** Moving and changing act on the press, so holding repeats. */
    private fun onKeyPressed(keyCode: Int) {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> step(+1)
            KeyEvent.KEYCODE_DPAD_DOWN -> step(-1)
            KeyEvent.KEYCODE_DPAD_LEFT -> moveTo((column + cells.size - 1) % cells.size)
            KeyEvent.KEYCODE_DPAD_RIGHT -> moveTo((column + 1) % cells.size)
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> typeDigit(keyCode - KeyEvent.KEYCODE_0)
        }
        render()
    }

    /** Saving and cancelling act on the release, so it never reaches the
     *  screen underneath. */
    private fun onKeyReleased(keyCode: Int) {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                val hour24 = ReportBuilder.to24Hour(hour12, pm)
                val refused = check?.invoke(dayIndex, hour24, minute)
                if (refused != null) {
                    android.widget.Toast.makeText(context, refused, android.widget.Toast.LENGTH_LONG).show()
                    return
                }
                dismiss()
                onPicked(dayIndex, hour24, minute)
            }
            // This phone's Clear key sends BACK.
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_SOFT_LEFT -> dismiss()
        }
    }

    private fun moveTo(newColumn: Int) {
        column = newColumn
        typed = ""
    }

    private fun step(by: Int) {
        typed = ""
        val count = days?.size ?: 1
        when (column - offset) {
            -1 -> dayIndex = (dayIndex + by + count) % count
            0 -> hour12 = (hour12 - 1 + by + 12) % 12 + 1
            1 -> minute = (minute + by + 60) % 60
            2 -> pm = !pm
        }
    }

    /** Two digits make the hour or minute; a digit that can't start one
     *  (e.g. 7 for minutes) stands alone and moves on. */
    private fun typeDigit(d: Int) {
        when (column - offset) {
            0 -> {
                val value = (typed + d).toInt()
                if (typed.isNotEmpty() && value in 1..12) {
                    hour12 = value
                    moveTo(offset + 1)
                } else if (d in 2..9) {
                    hour12 = d
                    moveTo(offset + 1)
                } else {
                    if (d >= 1) hour12 = d
                    typed = d.toString()
                }
            }
            1 -> {
                if (typed.isEmpty()) {
                    minute = d
                    if (d <= 5) typed = d.toString() else moveTo(offset + 2)
                } else {
                    minute = (typed + d).toInt()
                    moveTo(offset + 2)
                }
            }
        }
    }

    private fun render() {
        days?.let { cells[0].text = it[dayIndex] }
        cells[offset].text = hour12.toString()
        cells[offset + 1].text = "%02d".format(minute)
        cells[offset + 2].text = if (pm) "PM" else "AM"
        describe?.let { summary.text = it(dayIndex, ReportBuilder.to24Hour(hour12, pm), minute) }
        cells.forEachIndexed { i, cell ->
            cell.background = if (i == column) GradientDrawable().apply {
                setColor(theme.surface2)
                cornerRadius = dp(6).toFloat()
            } else null
        }
    }
}
