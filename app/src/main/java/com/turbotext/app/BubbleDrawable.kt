package com.turbotext.app

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable

/**
 * A message bubble: a rounded rectangle with the same radius on all four
 * corners, an optional tail curving off one bottom corner (toward the
 * sender's side, like a phone's stock messages app), an optional accent
 * stripe down one side, and an optional selection border.
 *
 * The tail takes the bottom [State.tailHeight] of the bounds; the view
 * adds that much bottom padding so its content stays in the body. The
 * fill, stripe and border all follow the same outline, tail included.
 *
 * The stripe is cut to the bubble's own outline (not drawn as a separate
 * square layer on top), so the corners on the stripe side stay round too.
 * Everything is drawn as anti-aliased paths rather than with clipPath,
 * which isn't anti-aliased on hardware-accelerated canvases.
 */
class BubbleDrawable(private val state: State) : Drawable() {

    class State(
        val fillColor: Int,
        val radius: Float,
        /** null = no stripe; true = left side; false = right side. */
        val stripeOnLeft: Boolean?,
        val stripeColor: Int,
        val stripeWidth: Float,
        val strokeColor: Int,
        /** 0 = no border. */
        val strokeWidth: Float,
        /** null = no tail; true = bottom-left corner; false = bottom-right. */
        val tailOnLeft: Boolean? = null,
        val tailWidth: Float = 0f,
        val tailHeight: Float = 0f,
    ) : ConstantState() {
        override fun newDrawable(): Drawable = BubbleDrawable(this)
        override fun getChangingConfigurations(): Int = 0
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = state.fillColor }
    private val stripePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = state.stripeColor }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = state.strokeColor
        strokeWidth = state.strokeWidth
    }

    private val outline = Path()
    private val stripe = Path()
    private val border = Path()

    override fun onBoundsChange(bounds: Rect) {
        val box = RectF(bounds)
        val r = state.radius
        outline.reset()
        outline.set(shape(box, r))

        stripe.reset()
        state.stripeOnLeft?.let { left ->
            val band = Path()
            band.addRect(
                if (left) RectF(box.left, box.top, box.left + state.stripeWidth, box.bottom)
                else RectF(box.right - state.stripeWidth, box.top, box.right, box.bottom),
                Path.Direction.CW
            )
            stripe.op(outline, band, Path.Op.INTERSECT)
        }

        // The border sits just inside the edge so none of it is cut off.
        border.reset()
        if (state.strokeWidth > 0f) {
            val half = state.strokeWidth / 2f
            val inner = RectF(box.left + half, box.top + half, box.right - half, box.bottom - half)
            border.set(shape(inner, (r - half).coerceAtLeast(0f)))
        }
    }

    /** The rounded body, plus the tail when there is one: the body stops
     *  [State.tailHeight] short of [box]'s bottom and the tail curves from
     *  its bottom edge down to a point under the outer corner. */
    private fun shape(box: RectF, r: Float): Path {
        val path = Path()
        val left = state.tailOnLeft
        val h = state.tailHeight
        if (left == null || h <= 0f) {
            path.addRoundRect(box, r, r, Path.Direction.CW)
            return path
        }
        val bodyBottom = box.bottom - h
        path.addRoundRect(RectF(box.left, box.top, box.right, bodyBottom), r, r, Path.Direction.CW)

        // Drawn for the right-hand corner, mirrored for the left. Both
        // ends of the tail start inside the body so the union is seamless.
        val w = state.tailWidth
        fun x(fromEdge: Float) = if (left) box.left + fromEdge else box.right - fromEdge
        val tail = Path()
        tail.moveTo(x(r * 0.5f + w), bodyBottom - 1f)
        tail.quadTo(x(r * 0.4f + w * 0.2f), bodyBottom + h * 0.8f, x(0f), box.bottom)
        tail.quadTo(x(r * 0.25f), bodyBottom + h * 0.25f, x(r * 0.2f), bodyBottom - r * 0.4f)
        tail.close()
        path.op(tail, Path.Op.UNION)
        return path
    }

    override fun draw(canvas: Canvas) {
        canvas.drawPath(outline, fillPaint)
        if (!stripe.isEmpty) canvas.drawPath(stripe, stripePaint)
        if (!border.isEmpty) canvas.drawPath(border, strokePaint)
    }

    override fun getConstantState(): ConstantState = state

    override fun setAlpha(alpha: Int) {
        fillPaint.alpha = alpha
        stripePaint.alpha = alpha
        strokePaint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        fillPaint.colorFilter = colorFilter
        stripePaint.colorFilter = colorFilter
        strokePaint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
