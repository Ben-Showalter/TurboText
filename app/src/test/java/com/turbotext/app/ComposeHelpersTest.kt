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
    fun sendLaterTimeIsOnThePickedDay() {
        val now = at(15, 0)
        assertEquals(at(16, 30), ScheduledSends.timeOn(now, 0, 16, 30, utc))
        assertEquals(at(9, 0) + 24 * 60 * 60 * 1000L, ScheduledSends.timeOn(now, 1, 9, 0, utc))
        assertEquals(at(20, 15) + 7 * 24 * 60 * 60 * 1000L, ScheduledSends.timeOn(now, 7, 20, 15, utc))
    }

    @Test
    fun sendLaterStartsOnTheNextHalfHour() {
        assertEquals(15 to 30, SendLaterPicker.nextHalfHour(at(15, 0), utc))
        assertEquals(16 to 0, SendLaterPicker.nextHalfHour(at(15, 30), utc))
        assertEquals(16 to 0, SendLaterPicker.nextHalfHour(at(15, 47), utc))
        assertEquals(0 to 0, SendLaterPicker.nextHalfHour(at(23, 40), utc))
    }

    @Test
    fun picturesSavedWithOnlyAWildcardTypeGetTheirRealType() {
        assertFalse(MediaSaver.isSpecific("image/*"))
        assertFalse(MediaSaver.isSpecific("application/octet-stream"))
        assertTrue(MediaSaver.isSpecific("image/png"))
        assertEquals("image/jpeg", MediaSaver.sniff(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())))
        assertEquals("image/png", MediaSaver.sniff(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A)))
        val mp4 = byteArrayOf(0, 0, 0, 0x18) + "ftypisom".toByteArray()
        assertEquals("video/mp4", MediaSaver.sniff(mp4))
        val threeGp = byteArrayOf(0, 0, 0, 0x18) + "ftyp3gp4".toByteArray()
        assertEquals("video/3gpp", MediaSaver.sniff(threeGp))
        assertEquals(null, MediaSaver.sniff(byteArrayOf(1, 2, 3)))
        // Nothing better known: a wildcard still saves as a viewable type.
        assertEquals("image/jpeg" to "jpg", MediaSaver.normalize("image/*") { null })
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
