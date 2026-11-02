package com.turbotext.app

import java.util.concurrent.atomic.AtomicLong

/**
 * Makes sure only the newest of several overlapping list reloads is shown.
 * Each reload takes a ticket from [start] before querying; when its query
 * finishes, it shows (and caches) the result only if [isLatest] says no
 * newer reload has started since. An older, slower query finishing last
 * used to put a stale list — missing the newest message — back on screen
 * and into the cache.
 */
class RefreshGate {
    private val generation = AtomicLong(0)

    fun start(): Long = generation.incrementAndGet()

    fun isLatest(ticket: Long): Boolean = generation.get() == ticket
}
