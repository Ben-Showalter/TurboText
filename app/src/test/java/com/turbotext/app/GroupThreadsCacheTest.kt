package com.turbotext.app

import org.junit.Assert.assertEquals
import org.junit.Test

class GroupThreadsCacheTest {
    private fun convo(id: Long, date: Long) = Conversation(id, "a,b", "G$id", "old", date, false)

    @Test
    fun newMessageMovesItsGroupToTheTop() {
        val list = listOf(convo(1, 300), convo(2, 200), convo(3, 100))
        val moved = GroupThreadsCache.moveToTop(list, convo(3, 400).copy(snippet = "new", unread = true))
        assertEquals(listOf(3L, 1L, 2L), moved.map { it.threadId })
        assertEquals("new", moved.first().snippet)
    }
}
