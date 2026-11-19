package com.turbotext.app

import org.junit.Assert.assertEquals
import org.junit.Test

class ReactionRowsTest {
    private val canDraw: (String) -> Boolean = { it in setOf("👍", "❤️", "😂", "😮", "😢") }

    @Test fun reactionsThenDividerThenChoices() {
        val rows = ReactionDisplay.rows(
            listOf(Reaction("❤️", "5551234567", false, 1), Reaction("👍", "You", true, 2)),
            name = { if (it == "5551234567") "Sarah" else it },
            recent = emptyList(), canDraw = canDraw
        )
        assertEquals(ReactionDisplay.Row("❤️  Sarah", "❤️"), rows[0])
        assertEquals(ReactionDisplay.Row("👍  You", "👍"), rows[1])
        assertEquals(ReactionDisplay.Row("— React —", null), rows[2])
        assertEquals(ReactionDisplay.Row("👍  ✓", "👍"), rows[3])
    }

    @Test fun newerEmojiPlusOneSendsTheStandIn() {
        val rows = ReactionDisplay.rows(listOf(Reaction("🫶", "Sam", false, 1)), { it }, emptyList(), canDraw)
        assertEquals(ReactionDisplay.Row("❤️  Sam", "❤️"), rows[0])
    }

    @Test fun noReactionsNoDivider() {
        val rows = ReactionDisplay.rows(emptyList(), { it }, emptyList(), canDraw)
        assertEquals("👍", rows[0].label)
    }
}
