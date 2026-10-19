package com.turbotext.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The small pure rules behind sending, scrolling, delivery and alerts. */
class MessageRulesTest {

    @Test
    fun readingBackIsNeverInterrupted() {
        // Browsing older messages: a reload leaves the list where it is.
        assertFalse(ScrollRules.shouldStickToBottom(wasEmpty = false, wasAtBottom = false, browsing = true, justSent = false))
        assertFalse(ScrollRules.shouldStickToBottom(wasEmpty = false, wasAtBottom = true, browsing = true, justSent = false))
        // At the newest message and not browsing: follow new messages.
        assertTrue(ScrollRules.shouldStickToBottom(wasEmpty = false, wasAtBottom = true, browsing = false, justSent = false))
        // First open, or this screen just sent: show the newest.
        assertTrue(ScrollRules.shouldStickToBottom(wasEmpty = true, wasAtBottom = false, browsing = false, justSent = false))
        assertTrue(ScrollRules.shouldStickToBottom(wasEmpty = false, wasAtBottom = false, browsing = true, justSent = true))
    }

    private fun msg(id: Long, body: String, date: Long, outgoing: Boolean = true) =
        Message(id = id, address = "5551234567", body = body, date = date, isOutgoing = outgoing)

    @Test
    fun aPendingBubbleMatchesItsRealRow() {
        val pending = msg(-1, "On my way", 1_000_000)
        assertTrue(PendingSends.matches(pending, msg(7, "On my way ", 1_000_500)))
        assertFalse(PendingSends.matches(pending, msg(7, "On my way", 1_000_500, outgoing = false)))
        assertFalse(PendingSends.matches(pending, msg(7, "Different", 1_000_500)))
        // An identical text sent well before this one isn't its row.
        assertFalse(PendingSends.matches(pending, msg(7, "On my way", 1_000_000 - 120_000)))
    }

    @Test
    fun smsDeliveryStatuses() {
        assertEquals(DeliveryStatus.Result.DELIVERED, DeliveryStatus.classify(0x00, false))
        assertEquals(DeliveryStatus.Result.DELIVERED, DeliveryStatus.classify(0x02, false))
        assertEquals(DeliveryStatus.Result.PENDING, DeliveryStatus.classify(0x20, false))
        assertEquals(DeliveryStatus.Result.FAILED, DeliveryStatus.classify(0x40, false))
        assertEquals(DeliveryStatus.Result.FAILED, DeliveryStatus.classify(0x65, false))
        // CDMA: error class in bits 24-25.
        assertEquals(DeliveryStatus.Result.DELIVERED, DeliveryStatus.classify(0, true))
        assertEquals(DeliveryStatus.Result.PENDING, DeliveryStatus.classify(2 shl 24, true))
        assertEquals(DeliveryStatus.Result.FAILED, DeliveryStatus.classify(3 shl 24, true))
    }

    @Test
    fun mmsMessageIdsMatchLoosely() {
        val id = com.android.mms.transaction.PushReceiver::normalizeMessageId
        assertEquals("abc123@mmsc", id("abc123@mmsc"))
        assertEquals("abc123@mmsc", id(" <abc123@mmsc> "))
        assertEquals("abc123@mmsc", id("\"abc123@mmsc\""))
        assertEquals("", id(null))
    }

    @Test
    fun doNotDisturbDecides() {
        val all = 1; val priority = 2; val none = 3; val alarms = 4
        val silent = 0; val vibrate = 1; val normal = 2
        assertEquals(DndHelper.Level.FULL, DndHelper.decide(all, normal, false))
        assertEquals(DndHelper.Level.SILENT, DndHelper.decide(none, normal, false))
        assertEquals(DndHelper.Level.SILENT, DndHelper.decide(alarms, normal, false))
        assertEquals(DndHelper.Level.SILENT, DndHelper.decide(priority, normal, false))
        assertEquals(DndHelper.Level.FULL, DndHelper.decide(priority, normal, true))
        assertEquals(DndHelper.Level.VIBRATE_ONLY, DndHelper.decide(all, vibrate, false))
        assertEquals(DndHelper.Level.SILENT, DndHelper.decide(all, silent, false))
        // Priority-only message rules: anyone / contacts / starred.
        assertTrue(DndHelper.senderAllowed(0, isContact = false, isStarred = false))
        assertTrue(DndHelper.senderAllowed(1, isContact = true, isStarred = false))
        assertFalse(DndHelper.senderAllowed(1, isContact = false, isStarred = false))
        assertTrue(DndHelper.senderAllowed(2, isContact = true, isStarred = true))
        assertFalse(DndHelper.senderAllowed(2, isContact = true, isStarred = false))
    }

    @Test
    fun ownNumberIsTheOneInEveryGroup() {
        val me = "5550000001"
        val lists = listOf(
            setOf(me, "5551111111", "5552222222"),
            setOf(me, "5553333333", "5554444444"),
            setOf(me, "5551111111", "5555555555"),
        )
        assertEquals(me, OwnNumber.pick(lists, 3))
        assertNull(OwnNumber.pick(lists.take(2), 3)) // too few to be sure
        // Two numbers in every group (Mom is in all of them too): no guess.
        assertNull(OwnNumber.pick(lists.map { it + "5559999999" }, 3))
        assertEquals("5551234567", OwnNumber.key("+1 (555) 123-4567"))
    }
}
