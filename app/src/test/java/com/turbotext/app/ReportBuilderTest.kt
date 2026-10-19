package com.turbotext.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.TimeZone

class ReportBuilderTest {

    private fun counts(sent: Int, received: Int) = ReportBuilder.Counts(sent, received)

    @Test
    fun busiestContactsComeFirst() {
        val rows = linkedMapOf(
            "Dad" to counts(2, 5),
            "Boby" to counts(45, 34),
            "Sam" to counts(10, 20),
        )
        assertEquals(
            "Report:\nBoby - 45 sent, 34 received\nSam - 10 sent, 20 received\nDad - 2 sent, 5 received",
            ReportBuilder.format("Report:", rows)
        )
    }

    @Test
    fun aQuietDaySaysSo() {
        assertEquals("Report:\nNo texts sent or received.", ReportBuilder.format("Report:", emptyMap()))
        assertEquals(
            "Report:\nNo texts sent or received.",
            ReportBuilder.format("Report:", mapOf("Sam" to counts(0, 0)))
        )
    }

    @Test
    fun nextTriggerIsLaterTodayOrTomorrow() {
        val utc = TimeZone.getTimeZone("UTC")
        val day = 24 * 60 * 60 * 1000L
        val tenAm = 10 * 60 * 60 * 1000L
        // 10:00 → 20:30 the same day.
        assertEquals(20 * 3_600_000L + 30 * 60_000L, ReportBuilder.nextTrigger(tenAm, 20, 30, utc))
        // 10:00 → 09:00 tomorrow.
        assertEquals(day + 9 * 3_600_000L, ReportBuilder.nextTrigger(tenAm, 9, 0, utc))
        // Exactly at the time → tomorrow, never "now" again.
        assertEquals(day + tenAm, ReportBuilder.nextTrigger(tenAm, 10, 0, utc))
    }

    @Test
    fun timesShowIn12HourForm() {
        assertEquals("8:30 PM", ReportBuilder.timeLabel12(20, 30))
        assertEquals("12:05 AM", ReportBuilder.timeLabel12(0, 5))
        assertEquals("12:00 PM", ReportBuilder.timeLabel12(12, 0))
        assertEquals(20, ReportBuilder.to24Hour(8, pm = true))
        assertEquals(0, ReportBuilder.to24Hour(12, pm = false))
        assertEquals(12, ReportBuilder.to24Hour(12, pm = true))
    }

    @Test
    fun contactsMatchHoweverTheNumberIsWritten() {
        val index = ContactIndex.from(listOf("(555) 123-4567" to "Boby", "+1 555 765 4321" to "Sam"))
        assertEquals("Boby", index.nameFor("+15551234567"))
        assertEquals("Boby", index.nameFor("5551234567"))
        assertEquals("Sam", index.nameFor("15557654321"))
        assertNull(index.nameFor("5550000000"))
    }
}
