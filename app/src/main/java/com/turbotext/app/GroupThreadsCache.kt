package com.turbotext.app

/** Caches the group thread list in memory, same reasoning as
 *  MessageCache — show what's cached instantly, then quietly refresh in
 *  the background, rather than paying the full query cost on every
 *  visit to this screen. */
object GroupThreadsCache {
    @Volatile private var cached: List<Conversation>? = null

    fun get(): List<Conversation>? = cached

    fun put(groups: List<Conversation>) {
        cached = groups
    }

    fun clear() {
        cached = null
    }

    /** A message just arrived in [threadId]: move it to the top with its
     *  new time and preview right away, before the full reload finishes.
     *  No-op if nothing is cached or the thread isn't in it. */
    @Synchronized
    fun bump(threadId: Long, date: Long, snippet: String) {
        val list = cached ?: return
        val row = list.firstOrNull { it.threadId == threadId } ?: return
        cached = moveToTop(list, row.copy(date = maxOf(date, row.date), snippet = snippet, unread = true))
    }

    /** [list] with [row] (same thread) first and the rest in order. */
    fun moveToTop(list: List<Conversation>, row: Conversation): List<Conversation> =
        listOf(row) + list.filter { it.threadId != row.threadId }
}
