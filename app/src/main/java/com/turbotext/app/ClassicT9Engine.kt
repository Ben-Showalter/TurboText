package com.turbotext.app

import android.content.Context

/** One node per digit position — only words sharing a given digit-prefix
 *  live under the same branch. */
private class TrieNode {
    val children = HashMap<Char, TrieNode>()
    // Words whose digit-code ends exactly at this node, most-common-first
    // (dictionary order), before any per-user learning reorders them.
    val words = mutableListOf<String>()
}

/**
 * TurboText's original predictor: a digit trie over assets/t9dict.txt
 * (word order = frequency order, no counts), with per-sequence "last 5
 * chosen" learning. Kept as a fallback while the TT9-based engine is
 * being proven on the phone.
 */
class ClassicT9Engine(context: Context, private val digitCode: (String) -> String) : WordPredictor {

    companion object {
        @Volatile private var cachedRoot: TrieNode? = null
    }

    private val learnPrefs = context.getSharedPreferences("message_pro_t9_learn", Context.MODE_PRIVATE)
    private val root: TrieNode

    init {
        val existing = cachedRoot
        if (existing != null) {
            root = existing
        } else {
            val newRoot = TrieNode()
            context.assets.open("t9dict.txt").bufferedReader().useLines { lines ->
                val seen = HashSet<String>()
                for (raw in lines) {
                    val word = raw.trim().lowercase()
                    if (word.isEmpty() || !seen.add(word)) continue
                    // Words with characters that aren't on a letter key
                    // (other than apostrophes, which are skipped) are left out.
                    if (!word.all { it in 'a'..'z' || it == '\'' }) continue
                    val code = digitCode(word)
                    if (code.isEmpty()) continue
                    nodeFor(newRoot, code, create = true)!!.words.add(word)
                }
            }
            root = newRoot
            cachedRoot = newRoot
        }
    }

    private fun nodeFor(from: TrieNode, digits: String, create: Boolean): TrieNode? {
        var node = from
        for (d in digits) {
            node = if (create) node.children.getOrPut(d) { TrieNode() } else node.children[d] ?: return null
        }
        return node
    }

    private fun collectWords(node: TrieNode, into: MutableList<String>) {
        into.addAll(node.words)
        for (child in node.children.values) collectWords(child, into)
    }

    override fun candidatesFor(digits: String, previousWord: String?): List<String> {
        if (digits.isEmpty()) return emptyList()
        val node = nodeFor(root, digits, create = false) ?: return emptyList()
        val result = mutableListOf<String>()
        collectWords(node, result)
        return applyLearnedOrder(result, digits)
    }

    override fun recordConfirmed(digits: String, word: String, previousWord: String?) {
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

    /** Inserts at the FRONT of its node — contacts and My Words outrank
     *  generic dictionary order. */
    override fun addPriorityWord(word: String) {
        val lower = word.trim().lowercase()
        val code = digitCode(lower)
        if (code.isEmpty()) return
        val node = nodeFor(root, code, create = true)!!
        node.words.remove(lower)
        node.words.add(0, lower)
    }

    override fun removeWord(word: String) {
        val lower = word.trim().lowercase()
        nodeFor(root, digitCode(lower), create = false)?.words?.remove(lower)
    }
}
