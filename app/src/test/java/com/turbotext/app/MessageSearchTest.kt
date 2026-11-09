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
}
