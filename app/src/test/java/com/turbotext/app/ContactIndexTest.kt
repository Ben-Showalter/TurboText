package com.turbotext.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContactIndexTest {

    @Test
    fun matchesHoweverTheNumberIsWritten() {
        val index = ContactIndex.from(listOf("(555) 123-4567" to "Bob"))
        assertEquals("Bob", index.nameFor("+15551234567"))
        assertEquals("Bob", index.nameFor("5551234567"))
    }

    @Test
    fun contactSavedWithoutAreaCode() {
        val index = ContactIndex.from(listOf("123-4567" to "Grandma"))
        assertEquals("Grandma", index.nameFor("+18141234567"))
    }

    @Test
    fun ambiguousSevenDigitEndingIsNotGuessed() {
        val index = ContactIndex.from(listOf("123-4567" to "Grandma", "(555) 123-4567" to "Bob"))
        assertEquals("Bob", index.nameFor("+15551234567"))
        assertNull(index.nameFor("+18141234567"))
    }

    @Test
    fun unknownNumbersAreWrittenReadably() {
        assertEquals("(555) 123-4567", ContactIndex.formatNumber("+15551234567"))
        assertEquals("(555) 123-4567", ContactIndex.formatNumber("5551234567"))
        assertEquals("22000", ContactIndex.formatNumber("22000"))
        assertEquals("bob@example.com", ContactIndex.formatNumber("bob@example.com"))
    }
}
