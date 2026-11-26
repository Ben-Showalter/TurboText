package com.turbotext.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MarkSuggestionsTest {
    private val marks = listOf(".", ",", "?", "!", "'")
    private val words: (String) -> List<String> = { keys -> if (keys == "43556") listOf("hello", "gekko", "hellp") else emptyList() }

    @Test fun loneOneIsDigitThenMarks() =
        assertEquals(listOf("1", ".", ",", "?", "!", "'"), MarkSuggestions.forDigits("1", marks, ".", null, words))

    @Test fun numberWithOnesIsJustDigits() =
        assertEquals(listOf("112"), MarkSuggestions.forDigits("112", marks, ".", null, words))

    @Test fun numberWithZeroIsJustDigits() =
        assertEquals(listOf("4910"), MarkSuggestions.forDigits("4910", marks, ".", null, words))

    @Test fun wordThenMark() {
        val out = MarkSuggestions.forDigits("435561", marks, "?", null, words)!!
        assertEquals("435561", out[0])
        assertEquals("hello?", out[1])
        assertEquals(listOf("hello?", "hello.", "hello,", "hello!", "hello'"), out.subList(1, 6))
        assertEquals(listOf("gekko?", "hellp?"), out.subList(6, 8))
    }

    @Test fun keepsTheHighlightedWord() =
        assertEquals("gekko.", MarkSuggestions.forDigits("435561", marks, ".", "gekko", words)!![1])

    @Test fun lettersGoToTheEngine() = assertNull(MarkSuggestions.forDigits("43556", marks, ".", null, words))
}
