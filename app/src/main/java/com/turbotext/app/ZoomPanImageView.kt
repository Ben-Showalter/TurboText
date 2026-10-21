package com.turbotext.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * A picture that can be zoomed and panned from the keypad — the same
 * viewer MatChat uses. It opens fitted to the screen and centred; [zoomIn]
 * and [zoomOut] step through 1×, 2×, 3×, 4× and 6× of that, the pan
 * methods move it 48 px at a time (never past the picture's edges), and
 * [resetView] goes back to fitted. Keys are wired up by the screen that
 * hosts it (MediaViewerActivity).
 */
class ZoomPanImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val drawMatrix = Matrix()
    private var bitmap: Bitmap? = null
    private var zoom = FIT
    private var panX = 0f
    private var panY = 0f

    fun setBitmap(bmp: Bitmap?) {
        bitmap = bmp
        zoom = FIT
        panX = 0f
        panY = 0f
        invalidate()
    }

    /** True once zoomed in past fitted, when Left/Right pan instead of
     *  turning the page. */
    val isZoomed: Boolean get() = zoom > FIT

    fun zoomIn() = stepZoom(+1)
    fun zoomOut() = stepZoom(-1)
    fun panLeft() = pan(PAN_STEP, 0f)
    fun panRight() = pan(-PAN_STEP, 0f)
    fun panUp() = pan(0f, PAN_STEP)
    fun panDown() = pan(0f, -PAN_STEP)

    fun resetView() {
        zoom = FIT
        panX = 0f
        panY = 0f
        invalidate()
    }

    private fun stepZoom(direction: Int) {
        val current = ZOOM_LEVELS.indexOfLast { it <= zoom + 0.01f }.coerceAtLeast(0)
        val next = (current + direction).coerceIn(0, ZOOM_LEVELS.lastIndex)
        zoom = ZOOM_LEVELS[next]
        if (zoom <= FIT) {
            panX = 0f
            panY = 0f
        }
        clampPan()
        invalidate()
    }

    private fun pan(dx: Float, dy: Float) {
        val beforeX = panX
        val beforeY = panY
        panX += dx
        panY += dy
        clampPan()
        if (panX != beforeX || panY != beforeY) invalidate()
    }

    private fun clampPan() {
        val bmp = bitmap ?: return
        val scale = fitScale(bmp) * zoom
        val maxX = maxOf(0f, (bmp.width * scale - width) / 2f)
        val maxY = maxOf(0f, (bmp.height * scale - height) / 2f)
        panX = panX.coerceIn(-maxX, maxX)
        panY = panY.coerceIn(-maxY, maxY)
    }

    private fun fitScale(bmp: Bitmap): Float =
        if (width == 0 || height == 0) 1f
        else minOf(width.toFloat() / bmp.width, height.toFloat() / bmp.height)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        clampPan()
    }

    override fun onDraw(canvas: Canvas) {
        val bmp = bitmap ?: return
        val scale = fitScale(bmp) * zoom
        val left = (width - bmp.width * scale) / 2f + panX
        val top = (height - bmp.height * scale) / 2f + panY
        drawMatrix.reset()
        drawMatrix.postScale(scale, scale)
        drawMatrix.postTranslate(left, top)
        canvas.drawBitmap(bmp, drawMatrix, paint)
    }

    private companion object {
        const val FIT = 1f
        const val PAN_STEP = 48f
        val ZOOM_LEVELS = floatArrayOf(1f, 2f, 3f, 4f, 6f)
    }
}
