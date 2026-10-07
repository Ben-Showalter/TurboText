package com.turbotext.app

import java.util.concurrent.atomic.AtomicLong

/** Bumped every time this app writes an incoming message into the SMS/MMS
 *  provider. ConversationPrewarm records the value from just before its
 *  query started, so it can tell whether a message landed while (or after)
 *  that query ran.
 *
 *  Needed because an incoming message is often what starts this process
 *  in the first place: TurboTextApplication's prewarm query then races the
 *  receiver's insert (an MMS download takes seconds), and a real log
 *  capture showed MainActivity later opening onto that snapshot with the
 *  new message missing — the prewarm handoff meant no live query ran. */
object ProviderChangeTracker {
    private val version = AtomicLong(0)

    fun current(): Long = version.get()

    /** Call synchronously right after the provider insert completes. */
    fun bump() {
        version.incrementAndGet()
    }
}
