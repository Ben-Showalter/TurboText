package com.turbotext.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** How cover-card text is cut and scrolled (docs/COVER-DISPLAY.md). */
class CoverTickerTest {

    @Test
    fun `short text is left alone`() {
        assertEquals("Ann: hi", coverDisplayText("Ann: hi", maxChars = 40))
    }

    @Test
    fun `long text is cut to the limit including the ellipsis`() {
        val shown = coverDisplayText("a".repeat(60), maxChars = 40)
        assertEquals(40, shown.length)
        assertTrue(shown.endsWith("…"))
    }

    @Test
    fun `a cut never leaves a space before the ellipsis`() {
        assertEquals("Ann: see…", coverDisplayText("Ann: see you", maxChars = 10))
    }

    @Test
    fun `text that fits the window is one frame`() {
        assertEquals(listOf("Ann: hi"), tickerFrames("Ann: hi", window = 14, pauseSteps = 3))
    }

    @Test
    fun `a pass holds the start, steps one character, holds the end, and never wraps`() {
        val frames = tickerFrames("abcdef", window = 4, pauseSteps = 2)
        assertEquals(listOf("abcd", "abcd", "bcde", "cdef", "cdef"), frames)
        assertTrue(frames.all { it.length == 4 })
    }

    @Test
    fun `the cover line puts the time first`() {
        assertEquals("1:05 PM · Ann: hi", coverLine("1:05 PM", "Ann: hi", maxChars = 40))
    }

    @Test
    fun `a long message is cut before the time goes in front`() {
        val line = coverLine("13:05", "a".repeat(60), maxChars = 40)
        assertTrue(line.startsWith("13:05 · "))
        assertEquals("13:05 · ".length + 40, line.length)
        assertTrue(line.endsWith("…"))
    }

    @Test
    fun `a scroll runs whole passes until it has run the minimum`() {
        // 15 s at 400 ms is 38 frames; passes of 20 frames need two.
        assertEquals(40, tickerFrameCount(passLength = 20, stepMs = 400, minDurationMs = 15_000))
    }

    @Test
    fun `a pass longer than the minimum runs once, all the way to its end`() {
        assertEquals(90, tickerFrameCount(passLength = 90, stepMs = 400, minDurationMs = 15_000))
    }

    @Test
    fun `an exact multiple adds no extra pass`() {
        assertEquals(40, tickerFrameCount(passLength = 10, stepMs = 500, minDurationMs = 20_000))
    }

    @Test
    fun `there is always at least one pass`() {
        assertEquals(5, tickerFrameCount(passLength = 5, stepMs = 400, minDurationMs = 0))
    }

    @Test
    fun `an ordinary long message is shown whole under the 120 cap`() {
        val message = "Ann: running late, the bus broke down near the bridge, see you around seven instead"
        assertEquals("1:05 PM · $message", coverLine("1:05 PM", message, maxChars = 120))
    }

    @Test
    fun `two-character steps still start at the beginning and land exactly on the end`() {
        val frames = tickerFrames("abcdefghij", window = 4, pauseSteps = 2, step = 2)
        // starts: 0,0 (hold), 2, 4, then 6,6 (end hold) — 6 is the last start.
        assertEquals(listOf("abcd", "abcd", "cdef", "efgh", "ghij", "ghij"), frames)
    }

    @Test
    fun `an odd distance to the end still ends on the last characters`() {
        val frames = tickerFrames("abcdefghi", window = 4, pauseSteps = 1, step = 2)
        // starts: 0, 2, 4, then 5 (the end) — never past it.
        assertEquals(listOf("abcd", "cdef", "efgh", "fghi"), frames)
        assertTrue(frames.all { it.length == 4 })
    }
}
