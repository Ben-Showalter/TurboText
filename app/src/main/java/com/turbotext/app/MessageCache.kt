package com.turbotext.app

/** Caches each thread's most-recently-loaded messages in memory. The SMS
 *  provider on this device has a fixed connection cost per query
 *  (measured earlier around ~2.7s) regardless of how much data is
 *  actually fetched — that's a hardware/OEM floor we can't query our way
 *  around. What we *can* do is avoid paying it again for a thread
 *  that's already been loaded this session: show the cached list
 *  instantly, then quietly refresh in the background. */
object MessageCache {
    private val cache = java.util.Collections.synchronizedMap(HashMap<Long, List<Message>>())

    fun get(threadId: Long): List<Message>? = cache[threadId]

    fun put(threadId: Long, messages: List<Message>) {
        cache[threadId] = messages
    }
}
