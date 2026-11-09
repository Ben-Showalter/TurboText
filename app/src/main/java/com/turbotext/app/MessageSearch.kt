package com.turbotext.app

/** Finding words in a conversation's messages (Options > Search Messages). */
object MessageSearch {
    /** Ids of [messages] whose text contains [query], any case, newest
     *  first. Every word must appear, in any order ("dinner sunday"
     *  finds "Sunday dinner?"). Attachment-only messages never match. */
    fun matches(messages: List<Message>, query: String): List<Long> {
        val words = query.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        return messages
            .filter { m -> m.body.isNotBlank() && words.all { m.body.lowercase().contains(it) } }
            .sortedByDescending { it.date }
            .map { it.id }
    }
}
