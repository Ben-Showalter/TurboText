package com.turbotext.app

/** Folding a thread's newest messages into the list already on screen. */
object MessageMerge {
    /** A message's identity: text and picture messages have separate id
     *  ranges that can overlap, so the type is part of it. */
    private fun key(m: Message) = (if (m.isMms) "mms:" else "sms:") + m.id

    /**
     * [existing] updated with [recent], the thread's newest messages read
     * just now: changed ones replaced (Sending → Sent → Delivered), new
     * ones added, and ones inside [recent]'s time span that are no longer
     * there dropped (deleted or trashed). Older messages are kept as they
     * are. Oldest first, like the thread.
     */
    fun merge(existing: List<Message>, recent: List<Message>): List<Message> {
        if (recent.isEmpty()) return existing
        val windowStart = recent.minOf { it.date }
        val recentKeys = recent.associateBy { key(it) }
        val kept = existing.filter { m -> m.date < windowStart || key(m) in recentKeys }
            .map { m -> recentKeys[key(m)] ?: m }
        val keptKeys = kept.mapTo(HashSet()) { key(it) }
        return (kept + recent.filter { key(it) !in keptKeys }).sortedBy { it.date }
    }
}
