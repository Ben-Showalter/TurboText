package com.turbotext.app

import org.junit.Assert.assertEquals
import org.junit.Test

class BackgroundContrastTest {
    private val black = 0xFF000000.toInt()
    private val white = 0xFFFFFFFF.toInt()

    @Test fun lightTextOnDark() = assertEquals(BackgroundContrast.LIGHT_TEXT, BackgroundContrast.textColorFor(0.1f))

    @Test fun darkTextOnLight() = assertEquals(BackgroundContrast.DARK_TEXT, BackgroundContrast.textColorFor(0.9f))

    @Test fun dimLayerCounts() {
        // A white picture under a mostly opaque black dim reads as dark.
        val lum = BackgroundContrast.luminance(BackgroundContrast.blend(white, black, 0x99))
        assertEquals(BackgroundContrast.LIGHT_TEXT, BackgroundContrast.textColorFor(lum))
    }

    @Test fun averagesTheCellsUnderTheText() {
        // Left half black, right half white, 2×1 grid.
        val grid = BackgroundContrast.fromPixels(intArrayOf(black, white), 2, 1, black, 0)
        assertEquals(0f, grid.brightnessUnder(0, 0, 50, 10, 100, 100), 0.01f)
        assertEquals(1f, grid.brightnessUnder(50, 0, 100, 10, 100, 100), 0.01f)
        assertEquals(0.5f, grid.brightnessUnder(0, 0, 100, 10, 100, 100), 0.01f)
    }
}
