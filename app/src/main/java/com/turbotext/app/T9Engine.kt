package com.turbotext.app

import android.content.Context

/** Reuses one engine (and its parsed dictionary) across the whole app
 *  instead of re-reading the word list every time a typing screen opens. */
object T9EngineHolder {
    @Volatile private var instance: T9Engine? = null
    fun get(context: Context): T9Engine {
        return instance ?: synchronized(this) {
            instance ?: T9Engine(context.applicationContext).also { instance = it }
        }
    }

    /** Drop the engine so the next get() builds one with the current
     *  settings (used when switching prediction engine). */
    fun reset() {
        synchronized(this) { instance = null }
    }
}

/**
 * T9 for every typing screen: key maps, multi-tap cycles, My Words, and
 * the word predictor behind the suggestion bar.
 *
 * Word prediction is delegated to a [WordPredictor] — by default
 * [Tt9WordEngine] (Traditional T9's dictionary and ranking/learning
 * rules); [ClassicT9Engine] (TurboText's original trie) can be chosen in
 * Advanced settings. Everything [T9InputController] calls is unchanged.
 *
 * On top of the predictor:
 *  - Contact names and My Words are always offered for their keys.
 *  - Fuzzy fallback: if nothing at all matches what was typed, try
 *    single-edit variants (one wrong digit, one missing, one extra, or two
 *    swapped) to catch slips like "wiling" for "willing".
 *  - The raw digits are always available as a candidate.
 */
class T9Engine(private val context: Context) {

    companion object {
        const val ENGINE_TT9 = "tt9"
        const val ENGINE_CLASSIC = "classic"
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

    private val letterToDigit: Map<Char, Char> = run {
        val map = HashMap<Char, Char>()
        for ((digit, letters) in keyLetters) {
            for (l in letters) map[l] = digit
        }
        map
    }

    private val userWordsPrefs = context.getSharedPreferences("message_pro_t9_user_words", Context.MODE_PRIVATE)

    /** Word just before the cursor, set by T9InputController before each
     *  lookup — the TT9 engine uses it for word-pair ordering. */
    @Volatile private var previousWord: String? = null

    private val predictor: WordPredictor =
        if (SettingsHelper.getPredictionEngine(context) == ENGINE_CLASSIC) {
            ClassicT9Engine(context, ::digitCodeFor)
        } else {
            Tt9WordEngine(context, ::digitCodeFor).also { migrateLegacyLearning(it) }
        }

    init {
        // Contact names, then My Words — real-world relevance to messaging
        // beats generic dictionary frequency.
        loadContactNames()
        loadUserAddedWords()
    }

    /** Carries over what the original engine learned (once). */
    private fun migrateLegacyLearning(engine: Tt9WordEngine) {
        val flags = context.getSharedPreferences("tt9_migration", Context.MODE_PRIVATE)
        if (flags.getBoolean("legacy_learning_imported", false)) return
        try {
            val legacy = context.getSharedPreferences("message_pro_t9_learn", Context.MODE_PRIVATE).all
                .mapNotNull { (k, v) ->
                    val words = (v as? String)?.split(",")?.filter { it.isNotEmpty() } ?: return@mapNotNull null
                    if (k.all { it in '2'..'9' } && words.isNotEmpty()) k to words else null
                }.toMap()
            engine.importLegacyLearning(legacy)
        } catch (e: Exception) {
            android.util.Log.w("TurboTextT9", "legacy learning import failed", e)
        }
        flags.edit().putBoolean("legacy_learning_imported", true).apply()
    }

    /** First/last names from Contacts, so they're predictable while
     *  texting. Parts that are already dictionary words are skipped:
     *  boosting them made a contact like "In Kim" or "At Home" turn
     *  every "in" or "at" into "In"/"At". Fails silently without the
     *  permission. */
    private fun loadContactNames() {
        try {
            context.contentResolver.query(
                android.provider.ContactsContract.Contacts.CONTENT_URI,
                arrayOf(android.provider.ContactsContract.Contacts.DISPLAY_NAME),
                null, null, null
            )?.use {
                val nameIndex = it.getColumnIndex(android.provider.ContactsContract.Contacts.DISPLAY_NAME)
                if (nameIndex < 0) return@use
                while (it.moveToNext()) {
                    val name = it.getString(nameIndex) ?: continue
                    for (part in name.split(Regex("\\s+"))) {
                        val cleaned = part.filter { c -> c.isLetter() || c == '\'' }
                        if (cleaned.length >= 2 && digitCodeFor(cleaned).length == cleaned.count { c -> c != '\'' } &&
                            !predictor.isDictionaryWord(cleaned)
                        ) {
                            predictor.addPriorityWord(cleaned)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // No READ_CONTACTS yet, or some other failure — non-fatal.
        }
    }

    private fun loadUserAddedWords() {
        val words = userWordsPrefs.getStringSet("words", emptySet()) ?: emptySet()
        for (w in words) predictor.addPriorityWord(w)
    }

    /** Adds a word to the dictionary immediately and keeps it across
     *  restarts. Returns false if it can't be typed on the keypad (needs
     *  2+ letters; apostrophes allowed). */
    fun addUserWord(word: String): Boolean {
        val cleaned = word.trim().lowercase().filter { it.isLetter() || it == '\'' }
        if (cleaned.length < 2) return false
        if (digitCodeFor(cleaned).length != cleaned.count { it != '\'' }) return false
        predictor.addPriorityWord(cleaned)
        val existing = userWordsPrefs.getStringSet("words", emptySet()) ?: emptySet()
        userWordsPrefs.edit().putStringSet("words", existing + cleaned).apply()
        return true
    }

    /** Words added via My Words — sorted for a stable list. */
    fun getUserWords(): List<String> {
        val words = userWordsPrefs.getStringSet("words", emptySet()) ?: emptySet()
        return words.sorted()
    }

    fun removeUserWord(word: String) {
        val existing = userWordsPrefs.getStringSet("words", emptySet()) ?: emptySet()
        userWordsPrefs.edit().putStringSet("words", existing - word).apply()
        predictor.removeWord(word)
    }

    /** Called by T9InputController before looking words up. */
    fun setPreviousWord(word: String?) {
        previousWord = word?.takeIf { it.isNotEmpty() }
    }

    /** Ranked words for [typedDigits], with the raw digits listed first —
     *  T9InputController skips past them to highlight the top word, and
     *  they're one Left press away (e.g. "43556" alongside "hello"). */
    fun candidatesFor(typedDigits: String): List<String> {
        if (typedDigits.isEmpty()) return emptyList()
        val words = predictor.candidatesFor(typedDigits, previousWord).ifEmpty { fuzzyCandidatesFor(typedDigits) }
        return if (words.contains(typedDigits)) words else listOf(typedDigits) + words
    }

    /** Single-edit-distance fallback — only when nothing matched at all. */
    private fun fuzzyCandidatesFor(typedDigits: String): List<String> {
        if (typedDigits.length < 3) return emptyList()
        val results = LinkedHashSet<String>()

        fun tryVariant(variant: String) {
            if (results.size >= 20 || variant.isEmpty()) return
            results.addAll(predictor.candidatesFor(variant, null).filter { digitCodeFor(it).length == variant.length })
        }

        // One wrong digit.
        for (i in typedDigits.indices) {
            for (d in '2'..'9') {
                if (d == typedDigits[i]) continue
                tryVariant(typedDigits.substring(0, i) + d + typedDigits.substring(i + 1))
            }
        }
        // One missing letter (typed word is one shorter than intended).
        for (i in 0..typedDigits.length) {
            for (d in '2'..'9') {
                tryVariant(typedDigits.substring(0, i) + d + typedDigits.substring(i))
            }
        }
        // One extra letter (typed word is one longer than intended).
        for (i in typedDigits.indices) {
            tryVariant(typedDigits.removeRange(i, i + 1))
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

    /** A word was confirmed for these digits — the predictor learns from it. */
    fun recordConfirmed(digits: String, word: String) {
        if (digits.isEmpty() || word.isEmpty() || word == digits) return
        predictor.recordConfirmed(digits, word, previousWord)
    }

    fun multiTapCharsFor(digit: Char): List<String> = multiTapCycles[digit] ?: emptyList()

    fun isLetterKey(digit: Char): Boolean = keyLetters.containsKey(digit)

    /** The T9 digit sequence for arbitrary text (letters only — anything
     *  else, including spaces and apostrophes, is skipped). */
    fun digitCodeFor(text: String): String {
        val sb = StringBuilder()
        for (ch in text.lowercase()) {
            letterToDigit[ch]?.let { sb.append(it) }
        }
        return sb.toString()
    }
}
