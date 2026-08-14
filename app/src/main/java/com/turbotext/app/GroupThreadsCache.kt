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
}
