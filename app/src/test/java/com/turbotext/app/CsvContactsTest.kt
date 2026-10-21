package com.turbotext.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CsvContactsTest {

    private fun numbers(r: CsvContacts.Result) = r.contacts.map { it.number }
    private fun names(r: CsvContacts.Result) = r.contacts.map { it.name }

    @Test
    fun headerWithNameAndPhone() {
        val r = CsvContacts.parse("Name,Phone\nBob Smith,(555) 123-4567\nSam,+1 555 222 3333\n")
        assertEquals(listOf("5551234567", "5552223333"), numbers(r))
        assertEquals(listOf("Bob Smith", "Sam"), names(r))
        assertEquals(0, r.invalid)
    }

    @Test
    fun numbersOnlyNoHeader() {
        val r = CsvContacts.parse("5551234567\r\n555-222-3333\r\n")
        assertEquals(listOf("5551234567", "5552223333"), numbers(r))
        assertEquals(listOf("", ""), names(r))
    }

    @Test
    fun phoneFirstThenNameWithoutHeader() {
        val r = CsvContacts.parse("5551234567,Bob\n5552223333,Sam\n")
        assertEquals(listOf("Bob", "Sam"), names(r))
    }

    @Test
    fun quotedFieldsBomAndSemicolons() {
        val r = CsvContacts.parse("﻿\"Customer\";\"Mobile\"\n\"Smith; Bob \"\"BJ\"\"\";\"555.123.4567\"\n")
        assertEquals(listOf("5551234567"), numbers(r))
        assertEquals(listOf("Smith; Bob \"BJ\""), names(r))
    }

    @Test
    fun firstAndLastNameColumns() {
        val r = CsvContacts.parse("First Name,Last Name,Cell,Notes\nBob,Smith,5551234567,vip\nSam,,5552223333,\n")
        assertEquals(listOf("Bob Smith", "Sam"), names(r))
    }

    @Test
    fun duplicatesAndInvalidRowsAreCounted() {
        val r = CsvContacts.parse("Name,Phone\nBob,5551234567\nBob again,1-555-123-4567\nNo number,\nBad,12\n")
        assertEquals(listOf("5551234567"), numbers(r))
        assertEquals(1, r.duplicates)
        assertEquals(2, r.invalid)
    }

    @Test
    fun phoneNumberRules() {
        assertEquals("5551234567", CsvContacts.phoneNumber("+1 (555) 123-4567"))
        assertNull(CsvContacts.phoneNumber("call 5551234567"))
        assertNull(CsvContacts.phoneNumber("123"))
    }

    @Test
    fun importReplacesMatchingNumbers() {
        val existing = listOf(BroadcastContact("Bob", "(555) 123-4567"), BroadcastContact("Sam", "5552223333"))
        val incoming = listOf(
            BroadcastContact("Robert Smith", "5551234567"),
            BroadcastContact("", "5552223333"),
            BroadcastContact("Ann", "5559998888")
        )
        val (merged, added, updated) = CsvContacts.merge(existing, incoming)
        assertEquals(
            listOf(
                BroadcastContact("Robert Smith", "5551234567"),
                BroadcastContact("Sam", "5552223333"),
                BroadcastContact("Ann", "5559998888")
            ),
            merged
        )
        assertEquals(1, added)
        assertEquals(2, updated)
    }

    @Test
    fun ownNumberNeedsTwoSendersAndNeverASender() {
        val me = "5550000000"
        // All from one sender: the other member is in every list too.
        val oneSender = List(3) { OwnNumber.Sample("5551111111", setOf(me, "5552222222")) }
        assertNull(OwnNumber.pickFrom(oneSender, 3))
        val twoSenders = listOf(
            OwnNumber.Sample("5551111111", setOf(me, "5552222222")),
            OwnNumber.Sample("5552222222", setOf(me, "5551111111")),
            OwnNumber.Sample("5551111111", setOf(me, "5552222222"))
        )
        assertEquals(me, OwnNumber.pickFrom(twoSenders, 3))
    }
}
