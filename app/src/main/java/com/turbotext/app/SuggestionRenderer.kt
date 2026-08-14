package com.turbotext.app

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.SpannableString
import android.text.Spannable
import android.text.style.ReplacementSpan

/** Draws its text on a rounded-rect background — Android's built-in
 *  BackgroundColorSpan only does plain rectangles, so a highlighted
 *  candidate with actual rounded corners needs custom drawing. */
class RoundedBackgroundSpan(
    private val backgroundColor: Int,
    private val textColor: Int
) : ReplacementSpan() {
    private val paddingHorizontal = 10f
    private val cornerRadius = 10f

    override fun getSize(
        paint: Paint, text: CharSequence, start: Int, end: Int, fm: Paint.FontMetricsInt?
    ): Int {
        return (paint.measureText(text, start, end) + paddingHorizontal * 2).toInt()
    }

    override fun draw(
        canvas: Canvas, text: CharSequence, start: Int, end: Int,
        x: Float, top: Int, y: Int, bottom: Int, paint: Paint
    ) {
        val width = paint.measureText(text, start, end)
        val rect = RectF(x, top.toFloat(), x + width + paddingHorizontal * 2, bottom.toFloat())
        val originalColor = paint.color
        paint.color = backgroundColor
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, paint)
        paint.color = textColor
        canvas.drawText(text, start, end, x + paddingHorizontal, y.toFloat(), paint)
        paint.color = originalColor
    }
}

object SuggestionRenderer {

    private const val WINDOW_SIZE = 5

    /** Builds the suggestions-bar text as a scrolling window of items
     *  (always including the selected one), with the selected item shown
     *  via a rounded highlighted background rather than brackets.
     *  windowSize defaults to 5 (right for word suggestions); callers
     *  showing narrower glyphs — punctuation, emoji — pass a larger
     *  value so the row actually fills the screen width instead of
     *  leaving it mostly empty. */
    fun build(candidates: List<String>, selected: Int, context: android.content.Context, windowSize: Int = WINDOW_SIZE): SpannableString {
        val theme = ThemeHelper.getCurrentTheme(context)
        val total = candidates.size
        var start = (selected - windowSize / 2).coerceAtLeast(0)
        start = start.coerceAtMost((total - windowSize).coerceAtLeast(0))
        val end = (start + windowSize).coerceAtMost(total)
        val shown = candidates.subList(start, end)

        val sb = StringBuilder()
        var highlightStart = -1
        var highlightEnd = -1
        for ((i, word) in shown.withIndex()) {
            val realIndex = start + i
            val wordStart = sb.length
            sb.append(word)
            if (realIndex == selected) {
                highlightStart = wordStart
                highlightEnd = sb.length
            }
            if (i < shown.size - 1) sb.append("  ")
        }

        val spannable = SpannableString(sb.toString())
        if (highlightStart >= 0) {
            spannable.setSpan(
                RoundedBackgroundSpan(theme.accentLight, theme.background),
                highlightStart, highlightEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        return spannable
    }
}
