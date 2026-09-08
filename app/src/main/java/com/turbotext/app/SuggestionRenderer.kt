package com.turbotext.app

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.text.SpannableString
import android.text.Spannable
import android.text.style.ReplacementSpan

/** Draws its text on a rounded-rect background — Android's built-in
 *  BackgroundColorSpan only does plain rectangles, so a highlighted
 *  candidate with actual rounded corners needs custom drawing.
 *
 *  Critically, getSize() reports the plain glyph width with NO extra
 *  padding: a ReplacementSpan's size feeds directly into line breaking,
 *  so if the highlight made its word wider than the same word unhighlighted,
 *  every Up/Down/Left/Right move would re-wrap the whole bar and shove a
 *  word onto another line. The rounded rect instead bleeds a few px past
 *  the glyphs into the inter-candidate gap (the "   " separator
 *  SuggestionRenderer puts between items leaves room for it) without
 *  affecting layout at all. */
class RoundedBackgroundSpan(
    private val backgroundColor: Int,
    private val textColor: Int
) : ReplacementSpan() {
    private val bleedHorizontal = 5f
    private val cornerRadius = 10f
    private val clipTmp = Rect()

    override fun getSize(
        paint: Paint, text: CharSequence, start: Int, end: Int, fm: Paint.FontMetricsInt?
    ): Int {
        // Round up, never down: under-reporting by even a pixel could let a
        // trailing word slip back onto this line the moment it's highlighted.
        return kotlin.math.ceil(paint.measureText(text, start, end)).toInt()
    }

    override fun draw(
        canvas: Canvas, text: CharSequence, start: Int, end: Int,
        x: Float, top: Int, y: Int, bottom: Int, paint: Paint
    ) {
        val width = paint.measureText(text, start, end)
        var left = x - bleedHorizontal
        var right = x + width + bleedHorizontal
        // TextView clips glyph drawing to its content box, so when the
        // highlighted word sits at the very start or end of a (wrapped)
        // line the oval's bleed would be sliced off flat — a chopped
        // corner. Pull the rect back inside the clip instead: the corner
        // stays fully rounded, the oval just hugs the text a touch tighter
        // on that side.
        if (canvas.getClipBounds(clipTmp)) {
            if (left < clipTmp.left) left = clipTmp.left.toFloat()
            if (right > clipTmp.right) right = clipTmp.right.toFloat()
        }
        val rect = RectF(left, top.toFloat(), right, bottom.toFloat())
        val originalColor = paint.color
        paint.color = backgroundColor
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, paint)
        paint.color = textColor
        canvas.drawText(text, start, end, x, y.toFloat(), paint)
        paint.color = originalColor
    }
}

object SuggestionRenderer {

    private const val WINDOW_SIZE = 5
    private const val ROWS = 3

    /** Between adjacent candidates. Wide enough that RoundedBackgroundSpan's
     *  highlight can bleed a few px past its word on both sides without
     *  touching the neighbours. Used by both build() and shownOffsets() so
     *  the offsets stay aligned with what's drawn. */
    private const val SEPARATOR = "   "

    /** Fallback only: T9InputController's Up/Down handling
     *  (selectCandidateAbove/Below) uses this fixed per-row estimate to
     *  move the highlight when the suggestions bar hasn't been laid out
     *  yet. Once it has, verticalNeighbor() below reads the real wrapped
     *  layout instead so the jump lands on the candidate actually drawn
     *  above/below. */
    fun columnsPerRow(windowSize: Int): Int = ((windowSize + ROWS - 1) / ROWS).coerceAtLeast(1)

    /** The [start, end) slice of [candidates] that build() shows for a
     *  given selection. Paged, NOT centered on [selected]: the visible set
     *  is fixed by which page [selected] falls in, so moving the highlight
     *  around within a page doesn't reflow the bar at all — the list only
     *  changes when the selection crosses a page boundary (a deliberate
     *  full-page turn), never a confusing one-word slide on every press.
     *  Kept as the single source of this math so build() and shownOffsets()
     *  below can never drift apart. */
    private fun windowRange(total: Int, selected: Int, windowSize: Int): IntRange {
        if (total <= windowSize) return 0 until total
        val lastPageStart = ((total - 1) / windowSize) * windowSize
        val start = ((selected.coerceAtLeast(0) / windowSize) * windowSize).coerceAtMost(lastPageStart)
        val end = (start + windowSize).coerceAtMost(total)
        return start until end
    }

    /** Builds the suggestions-bar text as one page of items (the page
     *  [selected] falls in — see windowRange), joined with plain separators
     *  and left to the TextView's own line-breaking to wrap — filling
     *  each line as full as the actual (variable-width) words allow
     *  before spilling to the next, rather than forcing a fixed number of
     *  items per row regardless of how much space they actually take.
     *  The view's own maxLines="3" caps it at 3 lines. The selected item
     *  is shown via a rounded highlighted background rather than brackets.
     *  windowSize defaults to 5 (right for word suggestions); callers
     *  showing narrower glyphs — punctuation, emoji — pass a larger
     *  value so more items fit per line before wrapping. */
    fun build(candidates: List<String>, selected: Int, context: android.content.Context, windowSize: Int = WINDOW_SIZE): SpannableString {
        val theme = ThemeHelper.getCurrentTheme(context)
        val range = windowRange(candidates.size, selected, windowSize)

        val sb = StringBuilder()
        var highlightStart = -1
        var highlightEnd = -1
        for ((i, realIndex) in range.withIndex()) {
            if (i > 0) sb.append(SEPARATOR)
            val wordStart = sb.length
            sb.append(candidates[realIndex])
            if (realIndex == selected) {
                highlightStart = wordStart
                highlightEnd = sb.length
            }
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

    /** Character offset where each shown candidate's text begins in
     *  build()'s output, keyed by its real index in [candidates]. Mirrors
     *  build()'s windowing and separators exactly so a caller can map a
     *  candidate index onto the bar's rendered Layout. */
    private fun shownOffsets(candidates: List<String>, selected: Int, windowSize: Int): Map<Int, Int> {
        val out = LinkedHashMap<Int, Int>()
        val sb = StringBuilder()
        for ((i, realIndex) in windowRange(candidates.size, selected, windowSize).withIndex()) {
            if (i > 0) sb.append(SEPARATOR)
            out[realIndex] = sb.length
            sb.append(candidates[realIndex])
        }
        return out
    }

    /** Index of the candidate drawn directly above ([direction] < 0) or
     *  below ([direction] > 0) the one at [currentIndex], in a bar that
     *  build() populated with these same arguments and that has since been
     *  laid out in [layout]. Picks the candidate on the adjacent wrapped
     *  line whose left edge is nearest the current one's — i.e. the word
     *  visually above/below. Returns null when the layout isn't ready or
     *  there's no line in that direction, the caller's cue to fall back to
     *  a fixed estimate. */
    fun verticalNeighbor(
        layout: android.text.Layout,
        candidates: List<String>,
        currentIndex: Int,
        direction: Int,
        windowSize: Int = WINDOW_SIZE
    ): Int? {
        val textLen = layout.text?.length ?: return null
        val offsets = shownOffsets(candidates, currentIndex, windowSize)
        val currentOffset = offsets[currentIndex] ?: return null
        if (currentOffset >= textLen) return null
        val currentLine = layout.getLineForOffset(currentOffset)
        val targetLine = currentLine + direction
        if (targetLine < 0 || targetLine >= layout.lineCount) return null
        val currentX = layout.getPrimaryHorizontal(currentOffset)

        var best: Int? = null
        var bestDx = Float.MAX_VALUE
        for ((index, offset) in offsets) {
            if (offset >= textLen) continue
            if (layout.getLineForOffset(offset) != targetLine) continue
            val dx = kotlin.math.abs(layout.getPrimaryHorizontal(offset) - currentX)
            if (dx < bestDx) {
                bestDx = dx
                best = index
            }
        }
        return best
    }
}