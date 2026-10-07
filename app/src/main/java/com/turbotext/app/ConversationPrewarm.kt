package com.turbotext.app

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** TurboTextApplication's startup thread already calls
 *  repo.getConversations() as its first step toward prewarming every
 *  thread's messages. MainActivity's own first-load query used to be a
 *  second, completely independent call to the same thing — on a cold
 *  start, both would fire within milliseconds of each other and end up
 *  contending for the same not-yet-warm provider connection, which a
 *  real log capture showed costing over 2 seconds instead of the usual
 *  well under a second.
 *
 *  This just lets MainActivity's very first load wait for the app-level
 *  fetch's result instead of starting its own — one query pays the
 *  cold-connection cost instead of two racing each other for it. */
object ConversationPrewarm {
    @Volatile private var result: List<Conversation>? = null
    @Volatile private var resultVersion: Long = STALE
    @Volatile private var consumed = false
    private val latch = CountDownLatch(1)

    /** [versionAtQueryStart] is ProviderChangeTracker.current() captured
     *  *before* the query began — pass [STALE] for a result that should
     *  never be used. */
    fun publish(conversations: List<Conversation>, versionAtQueryStart: Long) {
        result = conversations
        resultVersion = versionAtQueryStart
        latch.countDown()
    }

    /** Only does anything useful the first time it's called — after
     *  that it returns null immediately so callers fall through to a
     *  normal live query (this snapshot goes stale the moment new
     *  messages arrive, so it's not meant to be reused).
     *
     *  The 8000ms this used to default to was tuned for a fast device —
     *  a real log capture on a Kyocera E4610 showed the shared fetch
     *  itself taking well over a minute, so that timeout was firing
     *  and MainActivity was starting a second, fully redundant
     *  unbounded getConversations() scan *concurrently* with the first
     *  one still in flight, doubling the exact contention this class
     *  exists to avoid. Waiting longer for the one query already
     *  running is strictly better than racing a duplicate of it.
     *
     *  Also returns null when a message was written to the provider
     *  after the query started (see ProviderChangeTracker) — the
     *  snapshot may be missing it, so the caller's live query has to
     *  run instead. */
    @Synchronized
    fun consumeIfFresh(timeoutMs: Long = 60000): List<Conversation>? {
        if (consumed) return null
        consumed = true
        return try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            val snapshot = result
            if (snapshot != null && resultVersion != ProviderChangeTracker.current()) {
                android.util.Log.i("TurboTextPerf", "prewarm snapshot stale (messages arrived since), running live query")
                null
            } else {
                snapshot
            }
        } catch (e: InterruptedException) {
            null
        }
    }

    /** Never equals a real ProviderChangeTracker version (those start at 0). */
    const val STALE = -1L
}
