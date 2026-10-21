package com.turbotext.app

/** The part of T9 that turns key sequences into ranked words — swappable
 *  between the TT9-based engine and the original one (Settings →
 *  Advanced → Prediction engine). Key maps, multi-tap and My Words
 *  storage stay in [T9Engine]. */
interface WordPredictor {
    /** Ranked words for [digits]; [previousWord] is the word just before
     *  the cursor, when there is one (for word-pair learning). */
    fun candidatesFor(digits: String, previousWord: String?): List<String>

    /** The user chose [word] for [digits]. */
    fun recordConfirmed(digits: String, word: String, previousWord: String?)

    /** A contact name or My Words entry — always offered for its keys. */
    fun addPriorityWord(word: String)

    fun removeWord(word: String)

    /** True if [word] is in the built-in dictionary, ignoring case. */
    fun isDictionaryWord(word: String): Boolean
}
