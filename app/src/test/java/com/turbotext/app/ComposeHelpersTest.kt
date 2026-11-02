package com.turbotext.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class ComposeHelpersTest {

    @Test
    fun punctuationMostUsedFirstTiesKeepDefaultOrder() {
        val order = PunctuationUsage.order(mapOf("?" to 5, "@" to 2, "!" to 2))
        assertEquals(listOf("?", "!", "@", ".", ","), order.take(5))
        assertEquals(PunctuationUsage.DEFAULT.size, order.size)
        assertEquals(PunctuationUsage.DEFAULT, PunctuationUsage.order(emptyMap()))
    }

    private val utc = TimeZone.getTimeZone("UTC")

    private fun at(hour: Int, minute: Int): Long = Calendar.getInstance(utc).apply {
        clear()
        set(2026, Calendar.NOVEMBER, 2, hour, minute)
    }.timeInMillis

    @Test
    fun sendLaterIsTodayWhenStillAheadOtherwiseTomorrow() {
        val now = at(15, 0)
        assertEquals(at(16, 30), ScheduledSends.nextTime(now, 16, 30, utc))
        val tomorrow = ScheduledSends.nextTime(now, 9, 0, utc)
        assertEquals(at(9, 0) + 24 * 60 * 60 * 1000L, tomorrow)
        // The current minute itself counts as passed.
        assertEquals(now + 24 * 60 * 60 * 1000L, ScheduledSends.nextTime(now, 15, 0, utc))
        assertTrue(tomorrow - now <= 24 * 60 * 60 * 1000L)
    }

    @Test
    fun savedTypesAreCleanedUp() {
        val none: (String) -> String? = { null }
        assertEquals("image/jpeg" to "jpg", MediaSaver.normalize("image/jpg", none))
        assertEquals("image/jpeg" to "jpg", MediaSaver.normalize("IMAGE/JPEG; name=a.jpg", none))
        assertEquals("video/3gpp" to "3gp", MediaSaver.normalize("video/3gp", none))
        assertEquals("application/x-thing" to "bin", MediaSaver.normalize("application/x-thing", none))
        assertEquals("application/octet-stream" to "bin", MediaSaver.normalize(null, none))
    }

    @Test
    fun onlyTheNewestRefreshCounts() {
        val gate = RefreshGate()
        val first = gate.start()
        val second = gate.start()
        assertFalse(gate.isLatest(first))
        assertTrue(gate.isLatest(second))
    }
}
