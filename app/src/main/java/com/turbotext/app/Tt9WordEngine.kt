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
 * - **Learning** — every word you pick for its keys counts as a use, and
 *   the most used comes first (WordRanking). TT9 instead put whichever
 *   word was picked last on top, so "me" and "of" (both 6-3) kept trading
 *   places. Counts are halved when they grow large, so habits can change.
 * - **Word pairs** — counts which word you pick after which ("I" →
 *   "am"); once one has been picked there at least twice, and more than
 *   any other, it's offered first after that word.
 */
class Tt9WordEngine(private val context: Context, private val digitCode: (String) -> String) : WordPredictor {

    companion object {
        private const val TAG = "TurboTextT9"
        private const val ASSET = "t9/en.txt"

        // TT9's own limits (SettingsStatic).
        private const val SUGGESTIONS_MAX = 20
        private const val WORD_PAIR_MAX = 1250
        private const val WORD_PAIR_MAX_WORD_LENGTH = 6

        private const val PREFS_FREQ = "tt9_word_frequency"

        /** Contractions TT9's word list leaves out (TT9 builds them with
         *  the apostrophe on key 1; TurboText skips apostrophes, so they'd
         *  be untypeable). Frequencies are on the dictionary's own scale
         *  (the ≈ 222, in ≈ 210, go ≈ 164), so e.g. on 46 "I'm" sits
         *  between "in" and "go" until learning says otherwise. */
        private val CONTRACTIONS = mapOf(
            "I'm" to 190, "I'll" to 175, "I've" to 160, "I'd" to 140,
            "it's" to 175, "that's" to 170, "what's" to 150, "there's" to 145, "here's" to 130,
            "he's" to 140, "she's" to 140, "let's" to 150,
            "you're" to 165, "we're" to 150, "they're" to 150,
            "you'll" to 140, "we'll" to 140, "they'll" to 130,
            "you've" to 135, "we've" to 135, "they've" to 130,
            "he'd" to 120, "she'd" to 120, "you'd" to 125, "we'd" to 120, "they'd" to 120
        )
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
    /** How often each word (lower-case) has been picked for its keys:
     *  "u:word" in [freqPrefs]. What the order is decided by. */
    private val uses = HashMap<String, Int>()

    /** Contact names and My Words — not in the dictionary, always offered. */
    private val customBySeq = HashMap<String, MutableList<String>>()
    private val customFreq = HashMap<String, Int>()

    /** (previous word, next word's keys) → how often each next word was
     *  picked there. Oldest dropped first. */
    private val pairs = object : LinkedHashMap<String, MutableMap<String, Int>>(256, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MutableMap<String, Int>>?) = size > WORD_PAIR_MAX
    }
    private val pairPrefs = context.getSharedPreferences(PREFS_PAIRS, Context.MODE_PRIVATE)

    private var lastKey: String? = null
    private var lastResult: List<String> = emptyList()

    init {
        val all = freqPrefs.all
        for ((k, v) in all) {
            val f = (v as? Int) ?: continue
            if (k.startsWith("u:")) uses[k.substring(2)] = f
        }
        // Moving over from "last picked goes first": each word learned that
        // way starts with one use, and real use settles the order from here.
        if (!all.containsKey("uses_migrated")) {
            val edit = freqPrefs.edit()
            for (k in all.keys) {
                if (k.startsWith("w:") || k.startsWith("c:")) {
                    val w = k.substring(2).lowercase()
                    if (!uses.containsKey(w)) {
                        uses[w] = 1
                        edit.putInt("u:$w", 1)
                    }
                }
            }
            edit.putBoolean("uses_migrated", true).apply()
        }
        for ((word, freq) in CONTRACTIONS) {
            val seq = digitCode(word)
            customBySeq.getOrPut(seq) { mutableListOf() }.add(word)
            // How common it is; how often it's used is counted separately.
            if (!customFreq.containsKey(word)) customFreq[word] = freq
        }
        pairPrefs.getString("pairs", null)?.split('\n')?.forEach { line ->
            val parts = line.split('\t')
            // "prev, keys, word[, count]" (older saves have no count).
            if (parts.size >= 3) {
                val count = parts.getOrNull(3)?.toIntOrNull() ?: 1
                pairs.getOrPut(pairKey(parts[0], parts[1])) { HashMap() }[parts[2]] = count
            }
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

    /** How common the word is in English (the dictionary's frequency). */
    private fun freqOf(index: Int) = dict.freqs[index]

    private fun usesOf(word: String) = uses[word.lowercase()] ?: 0

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

    private data class Hit(val word: String, val seqLen: Int, val freq: Int, val index: Int, val uses: Int = 0)

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
            if (s.length <= maxLen) out.add(Hit(dict.words[i], s.length, freqOf(i), i, usesOf(dict.words[i])))
            i++
        }
        for ((cseq, list) in customBySeq) {
            if (cseq.startsWith(seq) && cseq.length <= maxLen) {
                for (w in list) out.add(Hit(w, cseq.length, customFreq[w] ?: topFrequency(cseq) + 1, -1, usesOf(w)))
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

        // Shortest (exact) first, then most used, then most common
        // (WordRanking). Case-insensitive duplicates ("Mark"/"mark") keep
        // the first one.
        val found = hits(digits)
        val ranked = WordRanking.order(found.map { WordRanking.Candidate(it.word, it.seqLen, it.uses, it.freq) })
        val sorted = ranked.map { c -> found.first { it.word == c.word && it.seqLen == c.seqLen } }
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
        // Single-letter words lead their key: "a" on 2, "I" on 4.
        '2' -> listOf("a", "b", "c"); '3' -> listOf("d", "e", "f"); '4' -> listOf("I", "g", "h")
        '5' -> listOf("j", "k", "l"); '6' -> listOf("m", "n", "o"); '7' -> listOf("p", "q", "r", "s")
        '8' -> listOf("t", "u", "v"); '9' -> listOf("w", "x", "y", "z")
        else -> emptyList()
    }

    private fun pairKey(word1: String, seq2: String) = "${word1.lowercase()}\t$seq2"

    /** If one word has clearly been picked most after [previousWord] for
     *  these keys (WordRanking.pairLeader), move it to the front. */
    private fun rearrangeByPair(words: List<String>, digits: String, previousWord: String?): List<String> {
        if (previousWord.isNullOrEmpty() || words.size < 2) return words
        val pairWord = pairs[pairKey(previousWord, digits)]?.let { WordRanking.pairLeader(it) } ?: return words
        val idx = words.indexOfFirst { it.equals(pairWord, ignoreCase = true) }
        if (idx <= 0) return words
        return listOf(words[idx]) + words.filterIndexed { i, _ -> i != idx }
    }

    override fun recordConfirmed(digits: String, word: String, previousWord: String?) {
        if (digits.isEmpty() || word.isEmpty()) return
        // Only words for exactly these keys — a longer completion is a
        // different word, not a rival for this key sequence. Every pick
        // counts, including when it was already the first guess.
        if (digitCode(word) != digits) return
        pairWithPrevious(previousWord, word, digits)
        addUse(word, digits)
        lastKey = null
    }

    private fun pairWithPrevious(previousWord: String?, word: String, seq: String) {
        if (previousWord.isNullOrEmpty()) return
        if (previousWord.equals(word, ignoreCase = true)) return
        if (previousWord.length > WORD_PAIR_MAX_WORD_LENGTH && word.length > WORD_PAIR_MAX_WORD_LENGTH) return
        if (!previousWord.all { it.isLetter() || it == '\'' } || !word.all { it.isLetter() || it == '\'' }) return
        val key = pairKey(previousWord, seq)
        val counts = pairs.remove(key) ?: HashMap()
        counts[word] = (counts[word] ?: 0) + 1
        pairs[key] = counts
        savePairs()
    }

    private fun savePairs() {
        val sb = StringBuilder()
        for ((k, counts) in pairs) {
            for ((w, n) in counts) {
                if (sb.isNotEmpty()) sb.append('\n')
                sb.append(k).append('\t').append(w).append('\t').append(n)
            }
        }
        pairPrefs.edit().putString("pairs", sb.toString()).apply()
    }

    /** One more use of [word] for [seq]. When the uses of all the words
     *  for [seq] add up past WordRanking.HALVE_AT, they're halved. */
    private fun addUse(word: String, seq: String) {
        val key = word.lowercase()
        uses[key] = (uses[key] ?: 0) + 1
        val edit = freqPrefs.edit().putInt("u:$key", uses[key]!!)
        val rivals = HashSet<String>()
        var i = lowerBound(seq)
        while (i < dict.seqs.size && dict.seqs[i] == seq) {
            rivals.add(dict.words[i].lowercase())
            i++
        }
        customBySeq[seq]?.forEach { rivals.add(it.lowercase()) }
        rivals.add(key)
        if (WordRanking.shouldHalve(rivals.sumOf { uses[it] ?: 0 })) {
            for (w in rivals) {
                val n = uses[w] ?: continue
                uses[w] = n / 2
                edit.putInt("u:$w", n / 2)
            }
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

    override fun isDictionaryWord(word: String): Boolean = indexOf(word) != null

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
            for (w in words.reversed()) addUse(w, seq)
        }
        lastKey = null
    }
}
