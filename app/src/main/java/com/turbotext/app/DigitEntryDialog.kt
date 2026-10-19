package com.turbotext.app

import android.app.Dialog
import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.Window
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Asks for digits typed on the keypad: a PIN (shown as dots), a time, or
 * a phone number (* types +). OK finishes; Clear deletes the last digit
 * and, with nothing left, cancels.
 */
class DigitEntryDialog(
    context: Context,
    title: String,
    private val hint: String,
    private val masked: Boolean = false,
    private val maxLength: Int = 15,
    private val allowPlus: Boolean = false,
    initial: String = "",
    private val onCancelled: () -> Unit = {},
    private val onDone: (String) -> Unit
) : Dialog(context) {

    private val theme = ThemeHelper.getCurrentTheme(context)
    private val typed = StringBuilder(initial)
    private val field = TextView(context)

    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        val pad = (12 * context.resources.displayMetrics.density).toInt()
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
        field.apply {
            setTextColor(theme.textPrimary)
            setBackgroundColor(theme.fieldBackground)
            textSize = 22f
            gravity = Gravity.CENTER
            setPadding(pad / 2, pad / 2, pad / 2, pad / 2)
        }
        root.addView(field, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = pad
            bottomMargin = pad / 2
        })
        root.addView(TextView(context).apply {
            text = hint
            setTextColor(theme.textSecondary)
            textSize = 12f
        })
        setContentView(root)
        window?.setBackgroundDrawable(ColorDrawable(theme.surface))
        window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        setOnKeyListener { _, keyCode, event ->
            // Acted on at release, so the release never reaches the screen
            // underneath (Back there would close it).
            if (event.action == KeyEvent.ACTION_UP) onKey(keyCode)
            true
        }
        render()
    }

    private fun onKey(keyCode: Int) {
        when (keyCode) {
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 ->
                if (typed.length < maxLength) typed.append('0' + (keyCode - KeyEvent.KEYCODE_0))
            KeyEvent.KEYCODE_STAR -> if (allowPlus && typed.isEmpty()) typed.append('+')
            // This phone's Clear key sends BACK.
            KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_BACK ->
                if (typed.isEmpty()) {
                    dismiss()
                    onCancelled()
                    return
                } else {
                    typed.deleteCharAt(typed.length - 1)
                }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_CALL -> {
                dismiss()
                onDone(typed.toString())
                return
            }
        }
        render()
    }

    private fun render() {
        field.text = if (masked) "•".repeat(typed.length) else typed.toString()
    }
}
