package com.turbotext.app

import org.junit.Assert.assertEquals
import org.junit.Test

class MessageMergeTest {
    private fun m(id: Long, date: Long, status: String? = null, mms: Boolean = false) =
        Message(id = id, address = "", body = "m$id", date = date, isOutgoing = status != null, isMms = mms, sendStatus = status)

    private fun ids(list: List<Message>) = list.map { (if (it.isMms) "mms" else "sms") + it.id }

    @Test
    fun newMessageAddedAndStatusReplaced() {
        val existing = listOf(m(1, 100), m(2, 200), m(3, 300, "sending"))
        val recent = listOf(m(2, 200), m(3, 300, "delivered"), m(4, 400))
        val merged = MessageMerge.merge(existing, recent)
        assertEquals(listOf("sms1", "sms2", "sms3", "sms4"), ids(merged))
        assertEquals("delivered", merged[2].sendStatus)
    }

    @Test
    fun deletedInsideWindowDroppedOlderKept() {
        val existing = listOf(m(1, 100), m(2, 200), m(3, 300), m(4, 400))
        // The newest read back from 2 on, and 3 is gone: it was deleted.
        // 1 is older than what was read, so it stays.
        val merged = MessageMerge.merge(existing, listOf(m(2, 200), m(4, 400)))
        assertEquals(listOf("sms1", "sms2", "sms4"), ids(merged))
    }

    @Test
    fun smsAndMmsWithSameIdAreDifferent() {
        val existing = listOf(m(7, 100))
        val merged = MessageMerge.merge(existing, listOf(m(7, 100), m(7, 150, mms = true)))
        assertEquals(listOf("sms7", "mms7"), ids(merged))
    }

    @Test
    fun nothingReadKeepsTheList() {
        val existing = listOf(m(1, 100))
        assertEquals(existing, MessageMerge.merge(existing, emptyList()))
    }
}
