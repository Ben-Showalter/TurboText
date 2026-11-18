package com.turbotext.app

import org.junit.Assert.assertEquals
import org.junit.Test

class ReactionDisplayTest {
    /** A phone that only draws these. */
    private val old = setOf("👍", "❤️", "😢", "😅", "🎉", "😮", "♥", "🍕")
    private val canDraw: (String) -> Boolean = { it in old }

    @Test fun drawableAsIs() = assertEquals("👍", ReactionDisplay.shown("👍", canDraw))

    @Test fun skinToneDropped() = assertEquals("👍", ReactionDisplay.shown("👍🏽", canDraw))

    @Test fun joinedTakesFirstPart() = assertEquals("❤️", ReactionDisplay.shown("❤️‍🔥", canDraw))

    @Test fun newerEmojiMapped() {
        assertEquals("❤️", ReactionDisplay.shown("🫶", canDraw))
        assertEquals("😢", ReactionDisplay.shown("🥹", canDraw))
    }

    @Test fun unknownFallsBackToHeart() = assertEquals(ReactionDisplay.FALLBACK, ReactionDisplay.shown("🪿", canDraw))

    @Test fun choicesMineThenRecentThenStandard() {
        val list = ReactionDisplay.choices(listOf("🍕", "👍"), "😮", canDraw)
        assertEquals(listOf("😮", "🍕", "👍", "❤️", "😢", "🎉"), list)
    }
}
