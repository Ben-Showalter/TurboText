package com.turbotext.app

/** Unlike ConversationPrewarm (a one-shot handoff useful only for the
 *  very first load after a cold process start), this is a plain cache
 *  that persists for as long as the process lives — which, on this
 *  device, is most of the time, since the process reliably survives
 *  closing the app. A real log capture showed the conversation-list
 *  query still costing 2+ seconds on a reopen just 3.5 seconds after
 *  closing, which rules out "connection went cold from inactivity" as
 *  the explanation — the query itself is just consistently expensive,
 *  every time it's run fresh. Showing whatever's cached immediately,
 *  then quietly replacing it once a live query finishes, means the
 *  user only ever waits on that cost once, not on every single open. */
object ConversationListCache {
    @Volatile private var cached: List<Conversation>? = null

    fun get(): List<Conversation>? = cached

    fun put(list: List<Conversation>) {
        cached = list
    }

    /** Optimistically clears the unread flag for one thread in the cache,
     *  without waiting for a live re-query. Called right when a
     *  conversation is opened so that backing out to the list shows it
     *  as read instantly instead of sitting on stale bold/dot state
     *  until the next full DB query finishes in the background. */
    fun markThreadRead(threadId: Long) {
        val list = cached ?: return
        cached = list.map { if (it.threadId == threadId && it.unread) it.copy(unread = false) else it }
    }
}
