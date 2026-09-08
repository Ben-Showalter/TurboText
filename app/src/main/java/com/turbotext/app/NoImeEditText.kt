package com.turbotext.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.Log
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
 * On the Kyocera E4811 that session handshake is what was throwing inside
 * the platform's own InputMethodManager (repeated "getShwoingNowFlag"
 * NoSuchElementException in logcat) — coinciding with the window losing
 * focus and dropped soft-key presses. onCheckIsTextEditor = false tells the
 * framework this view is never a text editor, so it never attempts to
 * start input at all, which sidesteps the bug entirely rather than just
 * hiding its symptom.
 *
 * That IME cutoff turned out to also take the platform's native cursor
 * rendering down with it, in a way that varied across the Android versions
 * this app actually ships on (7, 9, 10 confirmed on real devices) — a
 * second, native cursor rendering independently of (and getting stuck out
 * of sync with) whatever this class draws itself, regardless of
 * setCursorVisible(false)/setTextCursorDrawable(null) attempts to suppress
 * it. Rather than keep chasing that per-OS-version, the cursor here is
 * drawn via this View's ViewOverlay (see cursorDrawable below) — a
 * compositing layer the framework always draws strictly after this view's
 * own content and whatever the native Editor does internally, so it has no
 * relationship to that machinery to desync from in the first place.
 */
private const val TAG = "NoImeEditTextDebug"

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

    // The authoritative cursor offset, set explicitly by T9InputController
    // (see setCursorPosition) rather than read back from this view's own
    // selectionStart/selectionEnd. Those getters round-trip through the
    // same Editor/IME plumbing that's already unreliable on this hardware
    // per this class's doc comment — on-device testing showed they can't be
    // trusted to stay in sync with reality. This field is fed straight from
    // T9InputController's own `cursor`, which is never in doubt about where
    // it actually is.
    private var cursorPos = 0

    private val cursorPaint = Paint().apply {
        strokeWidth = 2f * resources.displayMetrics.density
    }

    // Drawn via this view's ViewOverlay (added in init below) rather than
    // from onDraw() — see this class's doc comment for why. draw() here
    // reads live view state (layout/scrollX/scrollY/cursorPos) fresh every
    // time, the same as the onDraw-based version used to, just composited
    // on a layer the native Editor can't interfere with.
    private val cursorDrawable = object : Drawable() {
        override fun draw(canvas: Canvas) {
            if (!ready || !isFocused || !cursorEnabled || !blinkOn) return
            val layout = layout ?: return
            val pos = cursorPos.coerceIn(0, text?.length ?: 0)
            val line = layout.getLineForOffset(pos)
            // No "- scrollX/scrollY" here: this drawable is drawn through the
            // view's ViewOverlay, which shares the same canvas pass as
            // onDraw() — the framework already applies a
            // canvas.translate(-scrollX, -scrollY) once for that whole pass
            // (that's how the plain text scrolls without TextView doing it
            // manually). Subtracting scroll again here double-counted it: at
            // scrollY=0 (before autoscroll engages) it was a no-op, which is
            // why the cursor tracked fine up through line 5, but the moment
            // scrollY went nonzero the cursor was drawn an extra scrollY
            // px too high — exactly the "stuck at line 5" symptom, even
            // though pos/line/scrollY were all advancing correctly underneath.
            val x = layout.getPrimaryHorizontal(pos) + totalPaddingLeft
            val top = (layout.getLineTop(line) + totalPaddingTop).toFloat()
            val bottom = (layout.getLineBottom(line) + totalPaddingTop).toFloat()
            Log.d(TAG, "cursorDrawable.draw: pos=$pos line=$line x=$x top=$top bottom=$bottom scrollY=$scrollY")
            canvas.drawLine(x, top, x, bottom, cursorPaint)
        }

        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}
        @Suppress("DEPRECATION")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    private val blinkHandler = Handler(Looper.getMainLooper())
    private val blinkRunnable = object : Runnable {
        override fun run() {
            blinkOn = !blinkOn
            cursorDrawable.invalidateSelf()
            blinkHandler.postDelayed(this, 500)
        }
    }

    init {
        super.setCursorVisible(false)
        cursorPaint.color = currentTextColor
        overlay.add(cursorDrawable)
        ready = true
    }

    override fun onCheckIsTextEditor(): Boolean = false

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        super.onCreateInputConnection(outAttrs)
        return null
    }

    /** setSelection() (still called by T9InputController.applyDisplay, kept
     *  for anything else in the framework that reads selection) triggers
     *  the platform's own bringPointIntoView() by default — a second,
     *  completely separate scroll-to-cursor mechanism running independently
     *  of onDraw's below, using its own internal metrics. On-device logging
     *  confirmed the two were fighting over scrollY. Suppressing it here
     *  leaves onDraw as the sole authority over scrollY. */
    override fun bringPointIntoView(offset: Int): Boolean = false

    override fun setCursorVisible(visible: Boolean) {
        cursorEnabled = visible
        if (!ready) return
        blinkHandler.removeCallbacks(blinkRunnable)
        if (visible && isFocused) {
            blinkOn = true
            blinkHandler.postDelayed(blinkRunnable, 500)
        }
        cursorDrawable.invalidateSelf()
    }

    override fun isCursorVisible(): Boolean = cursorEnabled

    /** Called by T9InputController after every text change with the exact
     *  offset it considers the cursor to be at — the single source of truth
     *  cursorDrawable positions the blink from. */
    fun setCursorPosition(pos: Int) {
        Log.d(TAG, "setCursorPosition($pos) text.length=${text?.length}")
        cursorPos = pos
        if (!ready) return
        cursorDrawable.invalidateSelf()
    }

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
        cursorDrawable.invalidateSelf()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // The overlay drawable has no inherent size of its own — without
        // this it defaults to an empty (0,0,0,0) bounds rect and never
        // draws anything at all.
        cursorDrawable.setBounds(0, 0, w, h)
    }

    override fun onDraw(canvas: Canvas) {
        // Scroll-into-view still has to live here — it needs to run on
        // every draw to keep pace with text changes, and both super.onDraw()
        // (the text) and cursorDrawable (the cursor, now drawn separately
        // via the overlay — see its own doc above) read the same scrollY
        // this sets. Actual cursor drawing no longer happens in this method.
        val layout = layout
        if (ready && isFocused && layout != null) {
            val pos = cursorPos.coerceIn(0, text?.length ?: 0)
            val line = layout.getLineForOffset(pos)
            val lineTop = layout.getLineTop(line)
            val lineBottom = layout.getLineBottom(line)
            val visibleHeight = height - paddingTop - paddingBottom
            val scrollYBefore = scrollY
            // A couple of px of buffer, not just the bare minimum scroll to
            // technically fit the line — on-device logging showed the
            // cursor landing with its bottom edge sitting exactly on
            // scrollY+visibleHeight (zero margin), right on the view's own
            // bottom clip boundary, and getting clipped away there —
            // logically the "right" position, but invisible. Keeping it a
            // few px clear of both edges avoids sitting flush against the
            // clip boundary at all.
            val margin = (2 * resources.displayMetrics.density).toInt()
            if (visibleHeight > 0) {
                when {
                    lineBottom - scrollY > visibleHeight - margin -> scrollTo(0, lineBottom - visibleHeight + margin)
                    lineTop < scrollY + margin -> scrollTo(0, (lineTop - margin).coerceAtLeast(0))
                }
            }
            Log.d(
                TAG,
                "onDraw scroll-calc: pos=$pos line=$line/${layout.lineCount} " +
                    "lineTop=$lineTop lineBottom=$lineBottom visibleHeight=$visibleHeight " +
                    "scrollY $scrollYBefore->$scrollY"
            )
        }

        super.onDraw(canvas)

        // scrollY may have just changed above — make sure the overlay
        // redraws at the corrected position rather than waiting for the
        // next unrelated invalidate (e.g. the next blink tick).
        cursorDrawable.invalidateSelf()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        if (!ready) return
        blinkHandler.removeCallbacks(blinkRunnable)
    }
}
