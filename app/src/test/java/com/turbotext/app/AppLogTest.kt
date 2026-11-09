package com.turbotext.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLogTest {

    @Test
    fun logLineTimesAreReadAndCompareInOrder() {
        val a = AppLog.stampOf("11-07 20:14:43.358  2514  3019 I TurboTextSend: sending via SMS")
        val b = AppLog.stampOf("11-07 20:14:45.476  2514  2514 I TurboTextMmsReport: text 23467 delivery")
        assertEquals("11-07 20:14:43.358", a)
        assertTrue(a!! < b!!)
        assertNull(AppLog.stampOf("read: unexpected EOF!"))
        assertNull(AppLog.stampOf("---- TurboText started (pid 2514, v1.0.7) ----"))
    }
}
