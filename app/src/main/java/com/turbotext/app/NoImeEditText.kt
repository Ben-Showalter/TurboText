package com.turbotext.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.EditText

/**
 * Every text field in this app is driven entirely by T9InputController off
 * raw physical key events (see its class doc) — setShowSoftInputOnFocus(false)
 * and windowSoftInputMode="stateAlwaysHidden" only stop the keyboard from
 * being drawn, they don't stop the framework from starting an IME session
 * the moment one of these fields gets focus (ComposeActivity does that
 * itself, e.g. requestFocus() when advancing from "To:" to the body).
 *
 * On the Kyocera E4811 (Android 10) that session handshake is what was
 * throwing inside the platform's own InputMethodManager (repeated
 * "getShwoingNowFlag" NoSuchElementException in logcat) — coinciding with
 * the window losing focus and dropped soft-key presses. onCheckIsTextEditor
 * = false tells the framework this view is never a text editor, so it never
 * attempts to start input at all, which sidesteps the bug entirely rather
 * than just hiding its symptom.
 *
 * That IME cutoff turned out to also take the platform's native cursor
 * rendering down with it (Editor's cursor-draw path is wired through the
 * same IME-connection plumbing) — and per T9InputController's own history
 * (see the removed hand-drawn-cursor note there), the native cursor was
 * never independently confirmed to render correctly on this exact hardware
 * in the first place. Rather than chase undocumented, OEM-patched
 * TextView/Editor internals, the cursor is drawn here directly: native
 * rendering is switched off permanently and this class owns the whole
 * blink cycle itself, so it can't be affected by IME state at all.
 */
class NoImeEditText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : EditText(context, attrs) {

    // TextView's own constructor applies XML attributes (android:cursorVisible,
    // android:textColor — every layout using this class sets at least one of
    // those) by calling the very methods overridden below, synchronously
    // during super(context, attrs) — before ANY of this subclass's own
    // property initializers have run. Every override touching
    // blinkHandler/cursorPaint/blinkRunnable checks this flag first and
    // no-ops until it flips true at the end of this class's init block;
    // without it those fields are still null at that point and the
    // constructor crashes outright (confirmed on-device).
    private var ready = false

    private var cursorEnabled = false
    private var blinkOn = true
    private val cursorPaint = Paint().apply {
        strokeWidth = 2f * resources.displayMetrics.density
    }
    private val blinkHandler = Handler(Looper.getMainLooper())
    private val blinkRunnable = object : Runnable {
        override fun run() {
            blinkOn = !blinkOn
            invalidate()
            blinkHandler.postDelayed(this, 500)
        }
    }

    init {
        super.setCursorVisible(false)
        cursorPaint.color = currentTextColor
        ready = true
    }

    override fun onCheckIsTextEditor(): Boolean = false

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        super.onCreateInputConnection(outAttrs)
        return null
    }

    override fun setCursorVisible(visible: Boolean) {
        cursorEnabled = visible
        if (!ready) return
        blinkHandler.removeCallbacks(blinkRunnable)
        if (visible && isFocused) {
            blinkOn = true
            blinkHandler.postDelayed(blinkRunnable, 500)
        }
        invalidate()
    }

    override fun isCursorVisible(): Boolean = cursorEnabled

    override fun setTextColor(color: Int) {
        super.setTextColor(color)
        if (!ready) return
        cursorPaint.color = color
    }

    override fun onFocusChanged(focused: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(focused, direction, previouslyFocusedRect)
        if (!ready) return
        blinkHandler.removeCallbacks(blinkRunnable)
        if (focused && cursorEnabled) {
            blinkOn = true
            blinkHandler.postDelayed(blinkRunnable, 500)
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!ready || !cursorEnabled || !blinkOn || !isFocused || selectionStart != selectionEnd) return
        val layout = layout ?: return
        val pos = selectionStart.coerceIn(0, text?.length ?: 0)
        val line = layout.getLineForOffset(pos)
        val x = layout.getPrimaryHorizontal(pos) + totalPaddingLeft - scrollX
        val top = (layout.getLineTop(line) + totalPaddingTop - scrollY).toFloat()
        val bottom = (layout.getLineBottom(line) + totalPaddingTop - scrollY).toFloat()
        canvas.drawLine(x, top, x, bottom, cursorPaint)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        if (!ready) return
        blinkHandler.removeCallbacks(blinkRunnable)
    }
}