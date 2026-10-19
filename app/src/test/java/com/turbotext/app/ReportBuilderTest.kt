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
    fun timesAreParsed() {
        assertEquals(20 to 30, ReportBuilder.parseTime("2030"))
        assertEquals(7 to 30, ReportBuilder.parseTime("730"))
        assertEquals(0 to 5, ReportBuilder.parseTime("00:05"))
        assertNull(ReportBuilder.parseTime("2460"))
        assertNull(ReportBuilder.parseTime("2500"))
        assertNull(ReportBuilder.parseTime("12"))
        assertEquals("07:05", ReportBuilder.timeLabel(7, 5))
    }
}
