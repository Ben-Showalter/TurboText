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
 * [onPicked] gets the 24-hour hour and the minute.
 */
class KeypadTimeDialog(
    context: Context,
    title: String,
    hour24: Int,
    minute: Int,
    private val onPicked: (hour24: Int, minute: Int) -> Unit
) : Dialog(context) {

    private val theme = ThemeHelper.getCurrentTheme(context)
    private var hour12 = if (hour24 % 12 == 0) 12 else hour24 % 12
    private var minute = minute
    private var pm = hour24 >= 12
    private var column = 0
    private val cells = List(3) { TextView(context) }

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
            cell.textSize = 26f
            cell.gravity = Gravity.CENTER
            cell.setTextColor(theme.textPrimary)
            cell.setPadding(dp(6), dp(4), dp(6), dp(4))
            row.addView(cell, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (i == 0) {
                row.addView(TextView(context).apply {
                    text = ":"
                    textSize = 26f
                    setTextColor(theme.textPrimary)
                })
            }
        }
        root.addView(row)
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
            KeyEvent.KEYCODE_DPAD_LEFT -> moveTo((column + 2) % 3)
            KeyEvent.KEYCODE_DPAD_RIGHT -> moveTo((column + 1) % 3)
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> typeDigit(keyCode - KeyEvent.KEYCODE_0)
        }
        render()
    }

    /** Saving and cancelling act on the release, so it never reaches the
     *  screen underneath. */
    private fun onKeyReleased(keyCode: Int) {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                dismiss()
                onPicked(ReportBuilder.to24Hour(hour12, pm), minute)
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
        when (column) {
            0 -> hour12 = (hour12 - 1 + by + 12) % 12 + 1
            1 -> minute = (minute + by + 60) % 60
            2 -> pm = !pm
        }
    }

    /** Two digits make the hour or minute; a digit that can't start one
     *  (e.g. 7 for minutes) stands alone and moves on. */
    private fun typeDigit(d: Int) {
        when (column) {
            0 -> {
                val value = (typed + d).toInt()
                if (typed.isNotEmpty() && value in 1..12) {
                    hour12 = value
                    moveTo(1)
                } else if (d in 2..9) {
                    hour12 = d
                    moveTo(1)
                } else {
                    if (d >= 1) hour12 = d
                    typed = d.toString()
                }
            }
            1 -> {
                if (typed.isEmpty()) {
                    minute = d
                    if (d <= 5) typed = d.toString() else moveTo(2)
                } else {
                    minute = (typed + d).toInt()
                    moveTo(2)
                }
            }
        }
    }

    private fun render() {
        cells[0].text = hour12.toString()
        cells[1].text = "%02d".format(minute)
        cells[2].text = if (pm) "PM" else "AM"
        cells.forEachIndexed { i, cell ->
            cell.background = if (i == column) GradientDrawable().apply {
                setColor(theme.surface2)
                cornerRadius = dp(6).toFloat()
            } else null
        }
    }
}
