package com.turbotext.app

/**
 * How bright each part of a Conversation Background looks on screen
 * (the picture with the dim layer over it), so each message's time line
 * can take a colour that contrasts with what's behind it: light over
 * dark areas, dark over light ones. A small grid of [cols]×[rows] cells
 * covering the message area; [brightness] is 0 (black) to 1 (white).
 */
class BackgroundContrast(val cols: Int, val rows: Int, private val brightness: FloatArray) {

    companion object {
        const val LIGHT_TEXT = 0xFFF2F2F2.toInt()
        const val DARK_TEXT = 0xFF1A1A1A.toInt()

        /** [pixels] is the picture shrunk to [cols]×[rows]; each is seen
         *  through [dim] at [dimAlpha] (0–255), as on screen. */
        fun fromPixels(pixels: IntArray, cols: Int, rows: Int, dim: Int, dimAlpha: Int): BackgroundContrast =
            BackgroundContrast(cols, rows, FloatArray(cols * rows) { luminance(blend(pixels[it], dim, dimAlpha)) })

        /** [over] at [alpha] (0–255) painted on [under]; opaque result. */
        fun blend(under: Int, over: Int, alpha: Int): Int {
            fun ch(shift: Int): Int {
                val u = (under shr shift) and 0xFF
                val o = (over shr shift) and 0xFF
                return (o * alpha + u * (255 - alpha)) / 255
            }
            return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }

        /** Perceived brightness, 0–1 (Rec. 601 weights). */
        fun luminance(color: Int): Float {
            val r = (color shr 16) and 0xFF
            val g = (color shr 8) and 0xFF
            val b = color and 0xFF
            return (0.299f * r + 0.587f * g + 0.114f * b) / 255f
        }

        /** Light text on a dark area, dark text on a light one. */
        fun textColorFor(lum: Float): Int = if (lum < 0.5f) LIGHT_TEXT else DARK_TEXT

        /** The faint outline behind [text]: the opposite colour, see-through. */
        fun shadowFor(text: Int): Int = if (text == LIGHT_TEXT) 0x99000000.toInt() else 0x99FFFFFF.toInt()
    }

    /** Average brightness under the area [left, top, right, bottom] of a
     *  [width]×[height] message area. */
    fun brightnessUnder(left: Int, top: Int, right: Int, bottom: Int, width: Int, height: Int): Float {
        if (width <= 0 || height <= 0) return 0f
        val c0 = (left.toLong() * cols / width).toInt().coerceIn(0, cols - 1)
        val c1 = ((right - 1).toLong() * cols / width).toInt().coerceIn(0, cols - 1)
        val r0 = (top.toLong() * rows / height).toInt().coerceIn(0, rows - 1)
        val r1 = ((bottom - 1).toLong() * rows / height).toInt().coerceIn(0, rows - 1)
        var sum = 0f
        var n = 0
        for (r in r0..maxOf(r0, r1)) for (c in c0..maxOf(c0, c1)) {
            sum += brightness[r * cols + c]
            n++
        }
        return if (n == 0) 0f else sum / n
    }
}
