package com.turbotext.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VerizonNetworkTest {
    @Test fun recognises() {
        assertTrue(VerizonNetwork.isVerizon("311480", null, null))
        assertTrue(VerizonNetwork.isVerizon("310260", "311480", "US Mobile"))
        assertTrue(VerizonNetwork.isVerizon(null, null, "Verizon Wireless"))
        assertFalse(VerizonNetwork.isVerizon("310260", "310260", "T-Mobile"))
    }

    @Test fun headers() {
        assertEquals(
            "x-up-calling-line-id: 12695551234|X-VzW-MDN: 12695551234",
            VerizonNetwork.httpParamsFor("+1 (269) 555-1234")
        )
        assertNull(VerizonNetwork.httpParamsFor("12345"))
    }

    @Test fun from() {
        assertEquals("+12695551234", VerizonNetwork.fromAddress("2695551234"))
        assertEquals("+12695551234", VerizonNetwork.fromAddress("12695551234"))
    }
}
