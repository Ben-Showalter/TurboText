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
 * corners, an optional accent stripe down one side, and an optional
 * selection border.
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
        outline.addRoundRect(box, r, r, Path.Direction.CW)

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
            val ir = (r - half).coerceAtLeast(0f)
            border.addRoundRect(inner, ir, ir, Path.Direction.CW)
        }
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
