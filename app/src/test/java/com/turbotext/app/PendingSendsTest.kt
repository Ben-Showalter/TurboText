package com.turbotext.app

import org.junit.Assert.assertEquals
import org.junit.Test

class PendingSendsTest {
    private val thread = 987654L

    private fun real(id: Long, body: String, date: Long) =
        Message(id = id, address = "", body = body, date = date, isOutgoing = true)

    @Test
    fun handedOverBubbleStaysUntilItsRealRowShows() {
        val key = PendingSends.add(thread, listOf("5551234567"), "on my way", emptyList())
        val sentAt = System.currentTimeMillis()
        PendingSends.finish(key, null, now = sentAt)
        // Read back before the row was saved: the bubble still shows.
        assertEquals(1, PendingSends.merge(thread, emptyList(), now = sentAt + 500).size)
        // The real row is there: only it shows, and the bubble is gone for good.
        val row = real(42, "on my way", sentAt)
        assertEquals(listOf(row), PendingSends.merge(thread, listOf(row), now = sentAt + 900))
        assertEquals(emptyList<Message>(), PendingSends.merge(thread, emptyList(), now = sentAt + 1000))
    }

    @Test
    fun handedOverBubbleDroppedAfterAMinuteWithoutItsRow() {
        val key = PendingSends.add(thread + 1, listOf("5551234567"), "lost one", emptyList())
        val sentAt = System.currentTimeMillis()
        PendingSends.finish(key, null, now = sentAt)
        assertEquals(1, PendingSends.merge(thread + 1, emptyList(), now = sentAt + 59_000).size)
        assertEquals(0, PendingSends.merge(thread + 1, emptyList(), now = sentAt + 61_000).size)
    }
}
