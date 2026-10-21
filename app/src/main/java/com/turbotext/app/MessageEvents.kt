package com.turbotext.app

import android.os.Handler
import android.os.Looper

/**
 * Tells an open conversation that its messages changed: a send went out
 * or failed, a delivery report came back, a message arrived. The
 * provider's own change notifications (ConversationActivity's
 * ContentObserver) proved unreliable on this phone, which is why a
 * bubble could sit on "Sending" until the thread was reopened. Every
 * receiver that writes a message row calls [changed] as well, so the
 * screen hears about it either way.
 *
 * In-process only: every receiver runs in the app's own process, so a
 * plain listener list is enough, with no broadcast to secure.
 */
object MessageEvents {
    /** [threadId] is the thread that changed, or null when unknown. */
    fun interface Listener {
        fun onMessagesChanged(threadId: Long?)
    }

    private val main = Handler(Looper.getMainLooper())
    private val listeners = java.util.concurrent.CopyOnWriteArraySet<Listener>()

    fun addListener(listener: Listener) {
        listeners.add(listener)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    /** Safe from any thread; listeners run on the main thread. */
    fun changed(threadId: Long? = null) {
        val id = threadId?.takeIf { it >= 0 }
        main.post { listeners.forEach { it.onMessagesChanged(id) } }
    }
}
