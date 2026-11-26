package com.turbotext.app

import org.junit.Assert.assertEquals
import org.junit.Test

class MmsAddressTest {
    @Test fun formattedNumbers() {
        assertEquals("+12693593836", MmsAddress.clean("+1 269-359-3836"))
        assertEquals("+12693593836", MmsAddress.clean("(269) 359-3836"))
        assertEquals("+12693593836", MmsAddress.clean("2693593836"))
        assertEquals("+12693593836", MmsAddress.clean("12693593836"))
        assertEquals("+12693593836", MmsAddress.clean("+12693593836"))
    }

    @Test fun othersUnchanged() {
        assertEquals("someone@example.com", MmsAddress.clean("someone@example.com"))
        assertEquals("22000", MmsAddress.clean("22000"))
    }

    @Test fun describe() {
        assertEquals("phone, cleaned", MmsAddress.describe("+1 269-359-3836", "+12693593836"))
        assertEquals("phone", MmsAddress.describe("+12693593836", "+12693593836"))
    }
}
