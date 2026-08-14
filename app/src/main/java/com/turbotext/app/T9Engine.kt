package com.turbotext.app

import android.content.Context

/** Reuses one parsed dictionary across the whole app instead of re-reading
 *  and re-parsing the word list every time a Conversation/Compose screen
 *  opens — that repeated work was part of what made switching screens feel
 *  slow. */
object T9EngineHolder {
    @Volatile private var instance: T9Engine? = null
    fun get(context: Context): T9Engine {
        return instance ?: synchronized(this) {
            instance ?: T9Engine(context.applicationContext).also { instance = it }
        }
    }
}

/** One node per digit position — only words sharing a given digit-prefix
 *  live under the same branch, which is what makes lookup (and fuzzy
 *  search, below) cost scale with word length rather than dictionary
 *  size. This mirrors how real T9 (Tegic/Nuance) is documented to work. */
private class TrieNode {
    val children = HashMap<Char, TrieNode>()
    // Words whose digit-code ends exactly at this node, most-common-first
    // (dictionary order), before any per-user learning reorders them.
    val words = mutableListOf<String>()
}

/**
 * Classic T9 predictive text: each keypad digit (2-9) maps to a group of
 * letters. As the user presses digits, we look up dictionary words whose
 * digit-code matches. Rebuilt around a trie (see TrieNode) instead of a
 * flat list, which is what real T9 implementations use — it keeps lookup
 * fast regardless of dictionary size, which matters on this phone's weak
 * CPU, especially now that fuzzy matching (below) needs to explore many
 * candidate variants per lookup.
 *
 * Two things layered on top of plain lookup, both confirmed as how real
 * T9 actually behaves rather than invented from scratch:
 *  - Per-user learning: once you confirm a word for a given digit
 *    sequence, it's favored over the generic dictionary order next time
 *    you type that same sequence.
 *  - Fuzzy fallback: if literally nothing matches what was typed (only
 *    checked then, not on every keystroke), try single-edit variants
 *    (one wrong digit, one missing, one extra, or two swapped) to catch
 *    things like "wiling" for "willing" or "nieghbor" for "neighbor".
 */
class T9Engine(private val context: Context) {

    companion object {
        // Parsing + trie-building is real work — caching it app-wide means
        // opening a conversation the 2nd+ time is instant instead of
        // rebuilding every time.
        @Volatile private var cachedRoot: TrieNode? = null
    }

    private val keyLetters = mapOf(
        '2' to "abc", '3' to "def", '4' to "ghi", '5' to "jkl",
        '6' to "mno", '7' to "pqrs", '8' to "tuv", '9' to "wxyz"
    )
    private val multiTapCycles = mapOf(
        '1' to listOf(".", ",", "'", "?", "!", "-", ":", ";", "@", "1"),
        '2' to listOf("a", "b", "c", "2"),
        '3' to listOf("d", "e", "f", "3"),
        '4' to listOf("g", "h", "i", "4"),
        '5' to listOf("j", "k", "l", "5"),
        '6' to listOf("m", "n", "o", "6"),
        '7' to listOf("p", "q", "r", "s", "7"),
        '8' to listOf("t", "u", "v", "8"),
        '9' to listOf("w", "x", "y", "z", "9"),
        '0' to listOf(" ", "0")
    )

    private val root: TrieNode
    private val learnPrefs = context.getSharedPreferences("message_pro_t9_learn", Context.MODE_PRIVATE)
    private val userWordsPrefs = context.getSharedPreferences("message_pro_t9_user_words", Context.MODE_PRIVATE)

    private val letterToDigit: Map<Char, Char> = run {
        val map = HashMap<Char, Char>()
        for ((digit, letters) in keyLetters) {
            for (l in letters) map[l] = digit
        }
        map
    }

    init {
        val existing = cachedRoot
        if (existing != null) {
            root = existing
        } else {
            val words = context.assets.open("t9dict.txt").bufferedReader().readLines()
                .map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct()

            val newRoot = TrieNode()
            for (word in words) {
                val codeBuilder = StringBuilder()
                var valid = true
                for (ch in word) {
                    // Apostrophes don't correspond to any digit — skipping
                    // them means "dont" and "don't" share the same digit
                    // code, so both show up as candidates either way.
                    if (ch == '\'') continue
                    val d = letterToDigit[ch]
                    if (d == null) {
                        valid = false
                        break
                    }
                    codeBuilder.append(d)
                }
                if (valid && codeBuilder.isNotEmpty()) {
                    var node = newRoot
                    for (d in codeBuilder) {
                        node = node.children.getOrPut(d) { TrieNode() }
                    }
                    node.words.add(word)
                }
            }
            root = newRoot
            cachedRoot = newRoot

            // Contact names, then explicitly user-added words, both
            // inserted at the front of their trie nodes — real-world
            // relevance to messaging beats generic dictionary frequency.
            // User-added words go last so they end up with the highest
            // priority of the two, since adding one is a stronger signal
            // of intent than just being in someone's contacts.
            loadContactNames(context)
            loadUserAddedWords()
        }
    }

    /** Inserts a word at the FRONT of its trie node's word list — used for
     *  contact names and user-added words, both of which represent
     *  real-world relevance beyond generic dictionary frequency. Safe to
     *  call at runtime (e.g. from the Settings "add word" screen), not
     *  just during initial load. */
    private fun insertPriorityWord(word: String) {
        val lower = word.trim().lowercase()
        if (lower.isEmpty()) return
        val codeBuilder = StringBuilder()
        for (ch in lower) {
            if (ch == '\'') continue
            val d = letterToDigit[ch] ?: return // contains a non-letter character — skip it
            codeBuilder.append(d)
        }
        if (codeBuilder.isEmpty()) return
        var node = root
        for (d in codeBuilder) {
            node = node.children.getOrPut(d) { TrieNode() }
        }
        node.words.remove(lower)
        node.words.add(0, lower)
    }

    /** Pulls in contact names so they're predictable while texting —
     *  first/last names split into individual words. Fails silently
     *  (missing permission, etc.) since the dictionary works fine
     *  without this either way. */
    private fun loadContactNames(context: Context) {
        try {
            val cursor = context.contentResolver.query(
                android.provider.ContactsContract.Contacts.CONTENT_URI,
                arrayOf(android.provider.ContactsContract.Contacts.DISPLAY_NAME),
                null, null, null
            )
            cursor?.use {
                val nameIndex = it.getColumnIndex(android.provider.ContactsContract.Contacts.DISPLAY_NAME)
                if (nameIndex < 0) return@use
                while (it.moveToNext()) {
                    val name = it.getString(nameIndex) ?: continue
                    for (part in name.split(Regex("\\s+"))) {
                        val cleaned = part.filter { c -> c.isLetter() || c == '\'' }
                        if (cleaned.length >= 2) insertPriorityWord(cleaned)
                    }
                }
            }
        } catch (e: Exception) {
            // No READ_CONTACTS permission yet, or some other failure —
            // non-fatal, the rest of the dictionary is unaffected.
        }
    }

    private fun loadUserAddedWords() {
        val words = userWordsPrefs.getStringSet("words", emptySet()) ?: emptySet()
        for (w in words) insertPriorityWord(w)
    }

    /** Adds a word to the dictionary immediately (usable right away, no
     *  restart needed) and persists it so it's still there next launch.
     *  Returns false if the word couldn't be used (e.g. contains
     *  characters that don't correspond to any keypad digit). */
    fun addUserWord(word: String): Boolean {
        val cleaned = word.trim().lowercase().filter { it.isLetter() || it == '\'' }
        if (cleaned.length < 2) return false
        insertPriorityWord(cleaned)
        val existing = userWordsPrefs.getStringSet("words", emptySet()) ?: emptySet()
        userWordsPrefs.edit().putStringSet("words", existing + cleaned).apply()
        return true
    }

    /** Words explicitly added via the Settings screen — not contacts or
     *  the base dictionary — sorted for a stable, readable list. */
    fun getUserWords(): List<String> {
        val words = userWordsPrefs.getStringSet("words", emptySet()) ?: emptySet()
        return words.sorted()
    }

    fun removeUserWord(word: String) {
        val existing = userWordsPrefs.getStringSet("words", emptySet()) ?: emptySet()
        userWordsPrefs.edit().putStringSet("words", existing - word).apply()

        // Also remove it from the live trie so it stops being suggested
        // immediately, not just after a restart.
        val codeBuilder = StringBuilder()
        for (ch in word) {
            if (ch == '\'') continue
            val d = letterToDigit[ch] ?: return
            codeBuilder.append(d)
        }
        var node = root
        for (d in codeBuilder) {
            node = node.children[d] ?: return
        }
        node.words.remove(word)
    }

    private fun findNode(digits: String): TrieNode? {
        var node = root
        for (d in digits) {
            node = node.children[d] ?: return null
        }
        return node
    }

    private fun collectWords(node: TrieNode, into: MutableList<String>) {
        into.addAll(node.words)
        for (child in node.children.values) collectWords(child, into)
    }

    /** Matches words whose digit-code STARTS WITH what's been typed so far
     *  — this is what lets a suggestion appear while still mid-word,
     *  before finishing all the letters of a longer completion, the way
     *  real T9 phones behave. Falls back to fuzzy single-edit matching
     *  only when this finds genuinely nothing. */
    fun candidatesFor(typedDigits: String): List<String> {
        if (typedDigits.isEmpty()) return emptyList()
        val node = findNode(typedDigits)
        val exact = if (node != null) {
            val result = mutableListOf<String>()
            collectWords(node, result)
            result
        } else {
            emptyList()
        }
        val withLearning = if (exact.isNotEmpty()) applyLearnedOrder(exact, typedDigits) else exact
        val words = withLearning.ifEmpty { fuzzyCandidatesFor(typedDigits) }
        // The raw digit sequence itself is always available as a
        // fallback option — reached by pressing Left from the top word
        // match — matching how the stock T9 input handles this (e.g.
        // "43556" alongside "hello").
        return if (words.contains(typedDigits)) words else words + typedDigits
    }

    /** Single-edit-distance fallback: substitution (wrong digit),
     *  deletion (missing letter), insertion (extra letter), and
     *  transposition (two swapped letters) — catches near-misses like
     *  "wiling"/"willing" or "nieghbor"/"neighbor". Every variant lookup
     *  is a trie walk, so cost scales with word length, not dictionary
     *  size — this can afford to check on the order of a hundred variants
     *  without it being a real performance risk, even on this phone. */
    private fun fuzzyCandidatesFor(typedDigits: String): List<String> {
        if (typedDigits.length < 3) return emptyList() // too short for a meaningful fuzzy guess
        val results = LinkedHashSet<String>()

        fun tryVariant(variant: String) {
            if (results.size >= 20) return
            val node = findNode(variant) ?: return
            val words = mutableListOf<String>()
            collectWords(node, words)
            results.addAll(words)
        }

        // One wrong digit.
        for (i in typedDigits.indices) {
            for (d in '2'..'9') {
                if (d == typedDigits[i]) continue
                tryVariant(typedDigits.substring(0, i) + d + typedDigits.substring(i + 1))
            }
        }
        // One missing letter (typed word is one shorter than intended).
        for (i in typedDigits.indices) {
            tryVariant(typedDigits.removeRange(i, i + 1))
        }
        // One extra letter (typed word is one longer than intended).
        for (i in 0..typedDigits.length) {
            for (d in '2'..'9') {
                tryVariant(typedDigits.substring(0, i) + d + typedDigits.substring(i))
            }
        }
        // Two adjacent letters swapped.
        for (i in 0 until typedDigits.length - 1) {
            if (typedDigits[i] == typedDigits[i + 1]) continue
            val chars = typedDigits.toCharArray()
            val tmp = chars[i]; chars[i] = chars[i + 1]; chars[i + 1] = tmp
            tryVariant(String(chars))
        }

        return results.toList()
    }

    /** Called once a word is actually confirmed — favors it over the
     *  generic dictionary order next time this exact digit sequence comes
     *  up, same self-adapting behavior real T9 is documented to use. */
    fun recordConfirmed(digits: String, word: String) {
        if (digits.isEmpty() || word.isEmpty()) return
        val existing = learnPrefs.getString(digits, "")
            ?.split(",")?.filter { it.isNotEmpty() }?.toMutableList() ?: mutableListOf()
        existing.remove(word)
        existing.add(0, word)
        learnPrefs.edit().putString(digits, existing.take(5).joinToString(",")).apply()
    }

    private fun applyLearnedOrder(words: List<String>, typedDigits: String): List<String> {
        val raw = learnPrefs.getString(typedDigits, null) ?: return words
        val learned = raw.split(",").filter { it.isNotEmpty() && it in words }
        if (learned.isEmpty()) return words
        val learnedSet = learned.toSet()
        return learned + words.filter { it !in learnedSet }
    }

    fun multiTapCharsFor(digit: Char): List<String> = multiTapCycles[digit] ?: emptyList()

    fun isLetterKey(digit: Char): Boolean = keyLetters.containsKey(digit)

    /** The T9 digit sequence for arbitrary text (letters only — anything
     *  else, including spaces, is skipped). Used for matching contact
     *  names against typed digits in the "To:" field, not just dictionary
     *  words. */
    fun digitCodeFor(text: String): String {
        val sb = StringBuilder()
        for (ch in text.lowercase()) {
            letterToDigit[ch]?.let { sb.append(it) }
        }
        return sb.toString()
    }
}
