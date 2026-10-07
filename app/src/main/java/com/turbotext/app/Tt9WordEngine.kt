/*
 * Prediction rules ported from Traditional T9 (https://github.com/sspanak/tt9),
 * Copyright Dimo Karaivanov (sspanak) and contributors, Apache License 2.0:
 *   - candidate lookup and ordering: db/sqlite/ReadOps.getSimilarWordPositions /
 *     getWordsQuery (exact + a few longer sequences, ORDER BY length, frequency)
 *   - learning: db/words/WordStore.makeTopWord and normalization
 *   - word pairs: ime/modes/predictions/WordPredictions.rearrangeByPairFrequency /
 *     pairWithPreviousWord and db/wordPairs (WordPair.isInvalid, WORD_PAIR_MAX)
 * The English word list (assets/t9/en.txt) is TT9's, see assets/t9/NOTICE.txt.
 *
 * Licensed under the Apache License, Version 2.0; see
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package com.turbotext.app

import android.content.Context
import android.util.Log

/**
 * Word prediction using Traditional T9's dictionary and ranking rules.
 *
 * TT9 itself keeps its dictionary in SQLite; here the same data is held in
 * three parallel arrays sorted by key sequence, which gives the same
 * answers without the database layer (and loads in one pass, since the
 * asset is pre-sorted).
 *
 * - **Lookup** — words for exactly the keys pressed come first, then a
 *   few longer words that start with those keys, each group most-common
 *   first. How many extra keys to look ahead depends on how much has been
 *   typed (TT9's "generations": 1 key for 2 typed, 2 for 3–4, any for 5+).
 * - **Learning** — choosing a word that wasn't the top suggestion gives it
 *   the top word's frequency + 1, so it's first next time ("makeTopWord").
 *   When frequencies get large they're scaled back down, so older habits
 *   can still be overtaken.
 * - **Word pairs** — remembers which word you picked after which ("I" →
 *   "am", "am" → "an"), so when the same keys follow the same word again,
 *   that word is offered first.
 */
class Tt9WordEngine(private val context: Context, private val digitCode: (String) -> String) : WordPredictor {

    companion object {
        private const val TAG = "TurboTextT9"
        private const val ASSET = "t9/en.txt"

        // TT9's own limits (SettingsStatic).
        private const val SUGGESTIONS_MAX = 20
        private const val WORD_FREQUENCY_MAX = 25500
        private const val WORD_FREQUENCY_NORMALIZATION_DIVIDER = 100
        private const val WORD_PAIR_MAX = 1250
        private const val WORD_PAIR_MAX_WORD_LENGTH = 6

        private const val PREFS_FREQ = "tt9_word_frequency"
        private const val PREFS_PAIRS = "tt9_word_pairs"

        // Parsed once per process — rebuilding on every screen open was
        // part of what made switching screens slow before.
        @Volatile private var cached: Dictionary? = null
    }

    private class Dictionary(val seqs: Array<String>, val words: Array<String>, val freqs: IntArray)

    private val dict: Dictionary = cached ?: synchronized(Tt9WordEngine::class.java) {
        cached ?: load().also { cached = it }
    }

    /** Learned frequencies, persisted by word — "w:word" for dictionary
     *  words, "c:word" for custom ones — so they survive a dictionary
     *  update that shifts word positions. Held in memory by index. */
    private val freqPrefs = context.getSharedPreferences(PREFS_FREQ, Context.MODE_PRIVATE)
    private val learned = HashMap<Int, Int>()

    /** Contact names and My Words — not in the dictionary, always offered. */
    private val customBySeq = HashMap<String, MutableList<String>>()
    private val customFreq = HashMap<String, Int>()

    /** (previous word, next word's keys) → next word. Oldest dropped first. */
    private val pairs = object : LinkedHashMap<String, String>(256, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > WORD_PAIR_MAX
    }
    private val pairPrefs = context.getSharedPreferences(PREFS_PAIRS, Context.MODE_PRIVATE)

    private var lastKey: String? = null
    private var lastResult: List<String> = emptyList()

    init {
        for ((k, v) in freqPrefs.all) {
            val f = (v as? Int) ?: continue
            when {
                k.startsWith("c:") -> customFreq[k.substring(2)] = f
                k.startsWith("w:") -> indexOf(k.substring(2))?.let { learned[it] = f }
            }
        }
        pairPrefs.getString("pairs", null)?.split('\n')?.forEach { line ->
            val parts = line.split('\t')
            if (parts.size == 3) pairs[pairKey(parts[0], parts[1])] = parts[2]
        }
    }

    private fun load(): Dictionary {
        val start = System.currentTimeMillis()
        val seqs = ArrayList<String>(180_000)
        val words = ArrayList<String>(180_000)
        val freqs = ArrayList<Int>(180_000)
        context.assets.open(ASSET).bufferedReader().useLines { lines ->
            for (line in lines) {
                if (line.isEmpty()) continue
                val tab = line.indexOf('\t')
                val word = if (tab < 0) line else line.substring(0, tab)
                val freq = if (tab < 0) 0 else line.substring(tab + 1).toIntOrNull() ?: 0
                val seq = digitCode(word)
                if (seq.isEmpty()) continue
                seqs.add(seq)
                words.add(word)
                freqs.add(freq)
            }
        }
        Log.i(TAG, "loaded ${words.size} words in ${System.currentTimeMillis() - start}ms")
        return Dictionary(seqs.toTypedArray(), words.toTypedArray(), freqs.toIntArray())
    }

    private fun freqOf(index: Int) = learned[index] ?: dict.freqs[index]

    /** Dictionary index of [word] (case-insensitive), or null. */
    private fun indexOf(word: String): Int? {
        val seq = digitCode(word)
        if (seq.isEmpty()) return null
        var i = lowerBound(seq)
        while (i < dict.seqs.size && dict.seqs[i] == seq) {
            if (dict.words[i].equals(word, ignoreCase = true)) return i
            i++
        }
        return null
    }

    /** First index whose sequence is >= [seq] (the asset is sorted). */
    private fun lowerBound(seq: String): Int {
        var lo = 0
        var hi = dict.seqs.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (dict.seqs[mid] < seq) lo = mid + 1 else hi = mid
        }
        return lo
    }

    private data class Hit(val word: String, val seqLen: Int, val freq: Int, val index: Int)

    /** Dictionary + custom words for [seq] and, depending on its length,
     *  a few longer sequences that start with it. */
    private fun hits(seq: String): List<Hit> {
        val generations = when (seq.length) {
            1 -> 0
            2 -> 1
            3, 4 -> 2
            else -> Int.MAX_VALUE
        }
        val maxLen = if (generations == Int.MAX_VALUE) Int.MAX_VALUE else seq.length + generations
        val out = ArrayList<Hit>()
        var i = lowerBound(seq)
        while (i < dict.seqs.size) {
            val s = dict.seqs[i]
            if (!s.startsWith(seq)) break
            if (s.length <= maxLen) out.add(Hit(dict.words[i], s.length, freqOf(i), i))
            i++
        }
        for ((cseq, list) in customBySeq) {
            if (cseq.startsWith(seq) && cseq.length <= maxLen) {
                for (w in list) out.add(Hit(w, cseq.length, customFreq[w] ?: topFrequency(cseq) + 1, -1))
            }
        }
        return out
    }

    private fun topFrequency(seq: String): Int {
        var top = 0
        var i = lowerBound(seq)
        while (i < dict.seqs.size && dict.seqs[i] == seq) {
            top = maxOf(top, freqOf(i))
            i++
        }
        return top
    }

    override fun candidatesFor(digits: String, previousWord: String?): List<String> {
        val key = "$digits|$previousWord"
        if (key == lastKey) return lastResult

        // Shortest (exact) first, then most frequent — TT9's
        // "ORDER BY LENGTH(word), frequency DESC". Case-insensitive
        // duplicates ("Mark"/"mark") keep the more frequent one.
        val sorted = hits(digits).sortedWith(compareBy<Hit> { it.seqLen }.thenByDescending { it.freq })
        val seen = HashSet<String>()
        val words = ArrayList<String>()
        for (h in sorted) {
            if (seen.add(h.word.lowercase())) words.add(h.word)
            if (words.size >= SUGGESTIONS_MAX) break
        }
        // A single key: also offer its letters, like TT9.
        if (digits.length == 1) {
            for (c in letters(digits[0])) if (seen.add(c)) words.add(c)
        }

        val result = rearrangeByPair(words, digits, previousWord)
        lastKey = key
        lastResult = result
        return result
    }

    private fun letters(digit: Char): List<String> = when (digit) {
        '2' -> listOf("a", "b", "c"); '3' -> listOf("d", "e", "f"); '4' -> listOf("g", "h", "i")
        '5' -> listOf("j", "k", "l"); '6' -> listOf("m", "n", "o"); '7' -> listOf("p", "q", "r", "s")
        '8' -> listOf("t", "u", "v"); '9' -> listOf("w", "x", "y", "z")
        else -> emptyList()
    }

    private fun pairKey(word1: String, seq2: String) = "${word1.lowercase()}\t$seq2"

    /** If the word last picked after [previousWord] for these keys is in
     *  the list, move it to the front. */
    private fun rearrangeByPair(words: List<String>, digits: String, previousWord: String?): List<String> {
        if (previousWord.isNullOrEmpty() || words.size < 2) return words
        val pairWord = pairs[pairKey(previousWord, digits)] ?: return words
        val idx = words.indexOfFirst { it.equals(pairWord, ignoreCase = true) }
        if (idx <= 0) return words
        return listOf(words[idx]) + words.filterIndexed { i, _ -> i != idx }
    }

    override fun recordConfirmed(digits: String, word: String, previousWord: String?) {
        if (digits.isEmpty() || word.isEmpty()) return
        val current = candidatesFor(digits, previousWord)
        // Already the first suggestion — guessed right, nothing to learn.
        if (current.firstOrNull() == word) return
        // Only words for exactly these keys — a longer completion is a
        // different word, not a rival for this key sequence.
        if (digitCode(word) != digits) return
        pairWithPrevious(previousWord, word, digits)
        makeTopWord(word, digits)
        lastKey = null
    }

    private fun pairWithPrevious(previousWord: String?, word: String, seq: String) {
        if (previousWord.isNullOrEmpty()) return
        if (previousWord.equals(word, ignoreCase = true)) return
        if (previousWord.length > WORD_PAIR_MAX_WORD_LENGTH && word.length > WORD_PAIR_MAX_WORD_LENGTH) return
        if (!previousWord.all { it.isLetter() || it == '\'' } || !word.all { it.isLetter() || it == '\'' }) return
        val key = pairKey(previousWord, seq)
        pairs.remove(key)
        pairs[key] = word
        savePairs()
    }

    private fun savePairs() {
        val sb = StringBuilder()
        for ((k, v) in pairs) {
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append(k).append('\t').append(v)
        }
        pairPrefs.edit().putString("pairs", sb.toString()).apply()
    }

    /** TT9's makeTopWord: the chosen word gets the current top word's
     *  frequency + 1 among words for exactly these keys. */
    private fun makeTopWord(word: String, seq: String) {
        val newTop = maxOf(topFrequency(seq), customBySeq[seq]?.maxOfOrNull { customFreq[it] ?: 0 } ?: 0) + 1
        val edit = freqPrefs.edit()
        val custom = customBySeq[seq]?.firstOrNull { it.equals(word, ignoreCase = true) }
        if (custom != null) {
            customFreq[custom] = newTop
            edit.putInt("c:$custom", newTop)
        } else {
            val found = indexOf(word) ?: return
            learned[found] = newTop
            edit.putInt("w:${dict.words[found].lowercase()}", newTop)
        }
        edit.apply()
        if (newTop > WORD_FREQUENCY_MAX) normalize(seq)
    }

    /** Scales every frequency for [seq] back down so learning can keep
     *  reordering it (TT9's normalization). */
    private fun normalize(seq: String) {
        val edit = freqPrefs.edit()
        var i = lowerBound(seq)
        while (i < dict.seqs.size && dict.seqs[i] == seq) {
            val f = freqOf(i) / WORD_FREQUENCY_NORMALIZATION_DIVIDER
            learned[i] = f
            edit.putInt("w:${dict.words[i].lowercase()}", f)
            i++
        }
        customBySeq[seq]?.forEach { w ->
            val f = (customFreq[w] ?: 0) / WORD_FREQUENCY_NORMALIZATION_DIVIDER
            customFreq[w] = f
            edit.putInt("c:$w", f)
        }
        edit.apply()
    }

    /** Contact names and My Words: offered for their keys even though
     *  they aren't dictionary words, ranked first until something else is
     *  learned over them. */
    override fun addPriorityWord(word: String) {
        val seq = digitCode(word)
        if (seq.isEmpty()) return
        val list = customBySeq.getOrPut(seq) { mutableListOf() }
        if (list.none { it.equals(word, ignoreCase = true) }) list.add(word)
        lastKey = null
    }

    override fun removeWord(word: String) {
        val seq = digitCode(word)
        customBySeq[seq]?.removeAll { it.equals(word, ignoreCase = true) }
        customFreq.remove(word)
        freqPrefs.edit().remove("c:$word").apply()
        lastKey = null
    }

    /** One-time import of what the old engine learned (top-5 words per key
     *  sequence, most recent first): each one is made top in reverse order,
     *  so the most recent ends up first. */
    fun importLegacyLearning(legacy: Map<String, List<String>>) {
        for ((seq, words) in legacy) {
            for (w in words.reversed()) makeTopWord(w, seq)
        }
        lastKey = null
    }
}
