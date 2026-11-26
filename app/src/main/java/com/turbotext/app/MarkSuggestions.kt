package com.turbotext.app

/**
 * The suggestions for sequences with the '1' key in T9 Word mode, from
 * TurboType (which got them from its owner):
 *  - a lone "1": the digit 1 on the far left, then the punctuation marks;
 *  - a word's keys then "1" (4-3-5-5-6-1): the number on the far left, then
 *    the chosen word with every mark (the likely one first), then the next
 *    few words with the likely mark: "43561", "hello.", "hello,", …;
 *  - any other sequence with 0 or 1: just the digits (no English word uses
 *    those keys, so guessing look-alike words only got in the way).
 * Null for ordinary letter sequences, which go to the word engine.
 */
object MarkSuggestions {

    /** Letter keys only (2-9). */
    fun isWordKeys(digits: String) = digits.isNotEmpty() && digits.all { it in '2'..'9' }

    /** A word's keys followed by a single '1'. */
    fun isWordThenMark(digits: String) =
        digits.length >= 2 && digits.last() == '1' && isWordKeys(digits.dropLast(1))

    /**
     * [marks] in the user's order; [likely] the guessed mark; [chosenWord]
     * the word highlighted when '1' was pressed; [words] looks up the
     * words for a key sequence.
     */
    fun forDigits(
        digits: String,
        marks: List<String>,
        likely: String,
        chosenWord: String? = null,
        words: (String) -> List<String>
    ): List<String>? {
        if (digits == "1") return listOf("1") + marks
        if (isWordThenMark(digits)) {
            val keys = digits.dropLast(1)
            val found = words(keys).filter { it != keys }
            val chosen = chosenWord?.takeIf { it in found } ?: found.firstOrNull() ?: return listOf(digits)
            val ordered = listOf(likely) + marks.filter { it != likely }
            return listOf(digits) +
                ordered.map { chosen + it } +
                found.filter { it != chosen }.take(3).map { it + likely }
        }
        if (digits.any { it == '0' || it == '1' }) return listOf(digits)
        return null
    }
}
