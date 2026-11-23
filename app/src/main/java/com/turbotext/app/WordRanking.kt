package com.turbotext.app

/**
 * How T9 Word orders its guesses: by how often you've used each word,
 * not by the one you picked last. Picking "of" once used to put it ahead
 * of "me" even after "me" had been picked a hundred times, so the first
 * guess kept flipping between them.
 */
object WordRanking {

    /** A guess: [seqLen] is the length of its key sequence (exact words
     *  first), [uses] how often you've picked it, [base] its dictionary
     *  frequency (how common it is in English). */
    data class Candidate(val word: String, val seqLen: Int, val uses: Int, val base: Int)

    /** When one key sequence's uses add up past this, they're all halved,
     *  so habits can still change over time. */
    const val HALVE_AT = 200

    /** Exact words first; then most used; then most common in English. */
    fun order(candidates: List<Candidate>): List<Candidate> =
        candidates.sortedWith(
            compareBy<Candidate> { it.seqLen }.thenByDescending { it.uses }.thenByDescending { it.base }
        )

    /** The word to move to the front after a given previous word: picked
     *  there at least twice, and more often than any other. */
    fun pairLeader(counts: Map<String, Int>): String? {
        val top = counts.maxByOrNull { it.value } ?: return null
        if (top.value < 2) return null
        if (counts.count { it.value == top.value } > 1) return null
        return top.key
    }

    fun shouldHalve(totalUses: Int) = totalUses > HALVE_AT
}
