package com.turbotext.app

/** Finding words in a conversation's messages (Options > Search Messages). */
object MessageSearch {
    /** The search's words, lower case. */
    fun words(query: String): List<String> =
        query.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }

    /** Ids of [messages] whose text contains [query], any case, newest
     *  first. Every word must appear, in any order ("dinner sunday"
     *  finds "Sunday dinner?"). Attachment-only messages never match. */
    fun matches(messages: List<Message>, query: String): List<Long> {
        val words = words(query)
        if (words.isEmpty()) return emptyList()
        return messages
            .filter { m -> m.body.isNotBlank() && words.all { m.body.lowercase().contains(it) } }
            .sortedByDescending { it.date }
            .map { it.id }
    }

    /** Where [words] occur in [text], any case, for highlighting: sorted,
     *  with overlapping or touching occurrences joined into one. */
    fun ranges(text: CharSequence, words: List<String>): List<IntRange> {
        if (text.isEmpty() || words.isEmpty()) return emptyList()
        val lower = text.toString().lowercase()
        val found = ArrayList<IntRange>()
        for (w in words) {
            if (w.isEmpty()) continue
            var from = 0
            while (true) {
                val at = lower.indexOf(w, from)
                if (at < 0) break
                found.add(at until at + w.length)
                from = at + 1
            }
        }
        found.sortBy { it.first }
        val merged = ArrayList<IntRange>()
        for (r in found) {
            val last = merged.lastOrNull()
            if (last != null && r.first <= last.last + 1) {
                merged[merged.size - 1] = last.first..maxOf(last.last, r.last)
            } else {
                merged.add(r)
            }
        }
        return merged
    }
}
