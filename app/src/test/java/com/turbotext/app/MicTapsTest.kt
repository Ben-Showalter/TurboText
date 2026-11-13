package com.turbotext.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MicTapsTest {
    @Test fun holdTranscribes() {
        val t = MicTaps()
        assertFalse(t.press(1000))
        assertEquals(MicTaps.Release.TRANSCRIBE, t.release(2500))
    }

    @Test fun quickDoubleTapStartsAudio() {
        val t = MicTaps()
        assertFalse(t.press(1000))
        assertEquals(MicTaps.Release.DROP, t.release(1100))
        assertTrue(t.press(1300))
        assertEquals(MicTaps.Release.IGNORE, t.release(1400))
        // The next press is a fresh one.
        assertFalse(t.press(1500))
    }

    @Test fun slowSecondTapIsNotDouble() {
        val t = MicTaps()
        t.press(1000)
        t.release(1100)
        assertFalse(t.press(2000))
        assertEquals(MicTaps.Release.TRANSCRIBE, t.release(3000))
    }
}
