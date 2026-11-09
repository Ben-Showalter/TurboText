package com.turbotext.app

import org.junit.Assert.assertEquals
import org.junit.Test

class MessageSearchTest {
    private fun msg(id: Long, body: String, date: Long) = Message(id = id, address = "", body = body, date = date, isOutgoing = false)

    @Test
    fun newestFirstAnyCaseEveryWord() {
        val messages = listOf(
            msg(1, "Sunday dinner?", 100),
            msg(2, "no plans", 200),
            msg(3, "Dinner at 6 on sunday", 300),
            msg(4, "", 400)
        )
        assertEquals(listOf(3L, 1L), MessageSearch.matches(messages, "dinner SUNDAY"))
        assertEquals(listOf(2L), MessageSearch.matches(messages, "plans"))
        assertEquals(emptyList<Long>(), MessageSearch.matches(messages, "   "))
    }

    @Test
    fun highlightRangesAnyCaseJoinedAndRepeated() {
        assertEquals(listOf(0..5, 10..15), MessageSearch.ranges("Dinner at DINNER", listOf("dinner")))
        // "sun" and "sunday" overlap: one highlight.
        assertEquals(listOf(0..5), MessageSearch.ranges("Sunday", listOf("sun", "sunday")))
        // Touching words join; separate ones don't.
        assertEquals(listOf(0..3, 5..7), MessageSearch.ranges("abcd efg", listOf("ab", "cd", "efg")))
        assertEquals(emptyList<IntRange>(), MessageSearch.ranges("", listOf("a")))
        assertEquals(emptyList<IntRange>(), MessageSearch.ranges("abc", emptyList()))
        assertEquals(listOf("dinner", "sunday"), MessageSearch.words("  Dinner   SUNDAY "))
    }
}
