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

    fun remove(threadId: Long) {
        cache.remove(threadId)
    }

    /** How many of the newest messages a live update reads. */
    private const val RECENT = 30

    /**
     * Brings a cached thread up to date by reading only its newest
     * messages and merging them in (MessageMerge) — well under a second,
     * where reading a long thread's whole history took up to 19 s and a
     * new message only appeared after it. Returns the merged list, or
     * null if the thread isn't cached (it's read in full when opened).
     * Off the main thread.
     */
    fun refreshRecent(context: android.content.Context, threadId: Long): List<Message>? {
        val existing = cache[threadId] ?: return null
        val recent = SmsRepository(context).getRecentMessages(threadId, RECENT)
        val merged = MessageMerge.merge(existing, recent)
        // Another refresh may have stored a newer list meanwhile; merging
        // into whatever is there now keeps both.
        synchronized(cache) {
            val now = cache[threadId] ?: return merged
            val result = if (now === existing) merged else MessageMerge.merge(now, recent)
            cache[threadId] = result
            return result
        }
    }
}
