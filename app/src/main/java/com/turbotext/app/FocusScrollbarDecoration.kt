package com.turbotext.app

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.view.View
import androidx.recyclerview.widget.RecyclerView

/**
 * MatChat-style scroll bar down the right edge of a D-pad list. The thumb
 * follows the *selected* item rather than the scroll offset, so it jumps
 * with the grey highlight on every Up/Down. Hidden when the whole list
 * fits on screen. Rows are inset by the bar's width so it never covers
 * them.
 *
 * [selectedPosition] returns the highlighted adapter position, or null
 * when nothing is selected.
 */
class FocusScrollbarDecoration(
    private val accentColor: Int,
    private val trackColor: Int,
    private val density: Float,
    private val selectedPosition: (RecyclerView) -> Int?
) : RecyclerView.ItemDecoration() {

    private val width = (5 * density).toInt()
    private val minThumb = 24 * density
    private val thumbPaint = Paint().apply { color = accentColor }
    private val trackPaint = Paint().apply { color = trackColor }

    override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
        outRect.set(0, 0, width, 0)
    }

    override fun onDrawOver(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        val count = parent.adapter?.itemCount ?: return
        if (count < 2 || parent.childCount >= count) return
        val pos = (selectedPosition(parent) ?: return).coerceIn(0, count - 1)

        val top = parent.paddingTop.toFloat()
        val height = (parent.height - parent.paddingTop - parent.paddingBottom).toFloat()
        val right = parent.width.toFloat() - parent.paddingRight
        val left = right - width

        val thumbH = maxOf(minThumb, height * parent.childCount / count).coerceAtMost(height)
        val thumbTop = top + (height - thumbH) * pos / (count - 1)

        c.drawRect(left, top, right, top + height, trackPaint)
        c.drawRect(left, thumbTop, right, thumbTop + thumbH, thumbPaint)
    }

    companion object {
        /** Adapter position of whichever row currently has focus. */
        fun focusedRow(rv: RecyclerView): Int? {
            val child = rv.focusedChild ?: return null
            return rv.getChildAdapterPosition(child).takeIf { it != RecyclerView.NO_POSITION }
        }
    }
}
