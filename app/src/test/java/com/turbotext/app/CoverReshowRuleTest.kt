package com.turbotext.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The rule that decides whether the Kyocera cover turning on (flip closed,
 *  or an outside button) re-shows the cover card (docs/COVER-DISPLAY.md). */
class CoverReshowRuleTest {

    private val card = 15_000L

    @Test
    fun `re-shows an unread card once the last one has expired`() {
        assertTrue(shouldReshow(hasPending = true, msSinceLastShown = card, cardDurationMs = card))
    }

    @Test
    fun `does nothing when nothing is unread`() {
        assertFalse(shouldReshow(hasPending = false, msSinceLastShown = card, cardDurationMs = card))
    }

    @Test
    fun `does not restart a card that is still on screen - our own post also wakes the cover`() {
        assertFalse(shouldReshow(hasPending = true, msSinceLastShown = card - 1, cardDurationMs = card))
    }
}
