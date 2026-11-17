package com.turbotext.app

/**
 * Which way a send result may move a message. A result only moves it
 * forward: a late or repeated answer (a watchdog's second try, a long
 * text's last part, a callback after a restart) can't turn a message
 * that's already Sent into Failed, or a failed part's text into Sent.
 *
 * Values are the providers' own: SMS TYPE (2 sent, 4 outbox, 5 failed,
 * 6 queued/waiting) and MMS MESSAGE_BOX (2 sent, 4 outbox, 5 failed).
 */
object SendState {
    const val SENT = 2
    const val OUTBOX = 4
    const val FAILED = 5
    const val QUEUED = 6

    /** A text's result: [current] is its TYPE now (null if unreadable).
     *  Sent: from Sending or Waiting, or from Failed when it's a
     *  one-part text the watchdog gave up on that went out after all.
     *  Failed: only from Sending or Waiting. */
    fun smsMayBecome(current: Int?, target: Int, partCount: Int): Boolean = when (target) {
        SENT -> current == OUTBOX || current == QUEUED || current == null || (current == FAILED && partCount <= 1)
        FAILED -> current == OUTBOX || current == QUEUED || current == null
        else -> true
    }

    /** A picture message's result: [current] is its MESSAGE_BOX now.
     *  Sent: from Sending, or from Failed (a try that was given up on
     *  went through). Failed: only from Sending. */
    fun mmsMayBecome(current: Int?, target: Int): Boolean = when (target) {
        SENT -> current == OUTBOX || current == FAILED || current == null
        FAILED -> current == OUTBOX || current == null
        else -> true
    }
}
