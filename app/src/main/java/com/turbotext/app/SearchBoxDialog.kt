package com.turbotext.app

import android.app.Activity
import android.app.Dialog
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog

/**
 * Search Messages' search box: a small panel over the conversation with
 * one line of T9 typing, and Search / Cancel softkeys. OK (or Call)
 * searches; Clear on an empty box, or Cancel, closes it. The mic button
 * dictates. [onSearch] gets the words.
 */
class SearchBoxDialog(
    private val activity: Activity,
    initialText: String,
    private val onSearch: (String) -> Unit
) : Dialog(activity) {

    private val theme = ThemeHelper.getCurrentTheme(activity)
    private val field = NoImeEditText(activity)
    private val suggestions = TextView(activity)
    private val modeLabel = TextView(activity)
    private val status = TextView(activity)
    private val controller: T9InputController
    private val voice = GroqVoiceInputHelper(activity)

    /** A release only counts if its press came here too: this opens on
     *  OK's press (from Options), and that OK's release must not search. */
    private val pressed = HashSet<Int>()

    private fun dp(v: Int) = (v * activity.resources.displayMetrics.density).toInt()

    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        val pad = dp(10)
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(theme.surface)
            setPadding(pad, pad, pad, dp(6))
        }

        // Title: the magnifying glass, then "Search".
        val title = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        title.addView(ImageView(activity).apply {
            setImageResource(R.drawable.ic_search)
            setColorFilter(theme.accent)
        }, LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(6) })
        title.addView(TextView(activity).apply {
            text = "Search"
            setTextColor(theme.textPrimary)
            textSize = 17f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        root.addView(title)

        // The box itself.
        field.apply {
            setShowSoftInputOnFocus(false)
            setSingleLine(true)
            textSize = 17f
            setTextColor(theme.textPrimary)
            setPadding(dp(8), dp(6), dp(8), dp(6))
            background = GradientDrawable().apply {
                setColor(theme.fieldBackground)
                setStroke(dp(2), theme.accent)
                cornerRadius = dp(6).toFloat()
            }
        }
        root.addView(field, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
        })
        suggestions.apply {
            textSize = 14f
            setPadding(0, dp(4), 0, 0)
            visibility = View.GONE
        }
        root.addView(suggestions)
        status.apply {
            setTextColor(theme.textSecondary)
            textSize = 12f
            visibility = View.GONE
        }
        root.addView(status)

        // Softkeys: typing mode | Search | Cancel.
        val bar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }
        fun label(view: TextView, text: String, gravity: Int, bold: Boolean = false) = view.apply {
            this.text = text
            this.gravity = gravity
            setTextColor(theme.accent)
            textSize = 14f
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        bar.addView(label(modeLabel, "", Gravity.START), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(label(TextView(activity), "Search", Gravity.CENTER, bold = true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(label(TextView(activity), "Cancel", Gravity.END), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(bar)

        setContentView(root)
        window?.setBackgroundDrawable(ColorDrawable(theme.surface))
        window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        window?.setGravity(Gravity.TOP)

        controller = T9InputController(
            engine = T9EngineHolder.get(activity),
            outputView = field,
            onModeChanged = { modeLabel.text = it },
            onSuggestionsChanged = { candidates, selected, windowSize ->
                if (candidates.isEmpty()) {
                    suggestions.visibility = View.GONE
                } else {
                    suggestions.visibility = View.VISIBLE
                    suggestions.text = SuggestionRenderer.build(candidates, selected, activity, windowSize)
                }
            },
            learnsWords = false,
            allowNewLines = false,
            suggestionsBarView = suggestions
        )
        if (initialText.isNotEmpty()) controller.setText(initialText)
        modeLabel.text = controller.currentLabel()
        controller.startCursorBlink()
        field.requestFocus()

        setOnKeyListener { _, keyCode, event ->
            when (event.action) {
                KeyEvent.ACTION_DOWN -> {
                    pressed.add(keyCode)
                    onKeyPressed(keyCode, event)
                }
                KeyEvent.ACTION_UP -> {
                    if (pressed.remove(keyCode)) onKeyReleased(keyCode) else true
                }
                else -> true
            }
        }
        setOnDismissListener {
            controller.stopCursorBlink()
            voice.cancelRecording()
        }
    }

    private fun onKeyPressed(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            // Acted on at release (below).
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_CALL,
            KeyEvent.KEYCODE_SOFT_LEFT, KeyEvent.KEYCODE_SOFT_RIGHT -> return true
            KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_BACK -> {
                // This phone's Clear key sends BACK: delete, or close when empty.
                if (controller.hasContent()) controller.onKeyDown(KeyEvent.KEYCODE_DEL, event) else dismiss()
                return true
            }
            in MicButtonKeyCodes.CODES -> {
                if (event.repeatCount == 0 && voice.startRecording()) showStatus("Listening…")
                return true
            }
        }
        controller.onKeyDown(keyCode, event)
        return true
    }

    private fun onKeyReleased(keyCode: Int): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_CALL -> search()
            KeyEvent.KEYCODE_SOFT_RIGHT -> dismiss()
            KeyEvent.KEYCODE_SOFT_LEFT -> chooseMode()
            in MicButtonKeyCodes.CODES -> {
                showStatus("Transcribing…")
                voice.stopRecordingAndTranscribe(
                    onResult = { text ->
                        status.visibility = View.GONE
                        if (text.isNotEmpty()) controller.appendVoiceResult(text)
                    },
                    onError = { message -> showStatus(message) }
                )
            }
            else -> controller.onKeyUp(keyCode)
        }
        return true
    }

    private fun showStatus(text: String) {
        status.text = text
        status.visibility = View.VISIBLE
    }

    /** OK: the word still showing as a suggestion counts, then search. */
    private fun search() {
        controller.confirmPending()
        val query = controller.currentText().trim()
        if (query.isEmpty()) return
        dismiss()
        onSearch(query)
    }

    private fun chooseMode() {
        val options = arrayOf("T9 Word", "ABC", "123")
        AlertDialog.Builder(activity)
            .setTitle("Typing Mode")
            .setItems(options) { _, which ->
                controller.selectMode(
                    when (which) {
                        0 -> InputMode.WORD
                        1 -> InputMode.MULTITAP
                        else -> InputMode.NUMBER
                    }
                )
            }
            .show()
    }
}
