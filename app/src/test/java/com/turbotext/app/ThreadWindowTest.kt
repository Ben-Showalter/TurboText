package com.turbotext.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThreadWindowTest {
    private fun msgs(n: Int) = (1..n).map { Message(it.toLong(), "1", "m$it", it.toLong(), false) }

    @Test fun keepsNewest() {
        val (shown, more) = ThreadWindow.trim(msgs(5), 3)
        assertEquals(listOf(3L, 4L, 5L), shown.map { it.id })
        assertTrue(more)
    }

    @Test fun shortThreadWhole() = assertFalse(ThreadWindow.trim(msgs(3), 3).second)

    @Test fun allShowsEverything() = assertEquals(500, ThreadWindow.trim(msgs(500), 0).first.size)

    @Test fun cycles() {
        assertEquals(500, ThreadWindow.next(200))
        assertEquals(0, ThreadWindow.next(500))
        assertEquals(100, ThreadWindow.next(0))
    }
}
