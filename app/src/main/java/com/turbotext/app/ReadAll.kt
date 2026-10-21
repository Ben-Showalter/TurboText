package com.turbotext.app

import android.content.Context
import android.widget.Toast

/** Options > "Mark All as Read", on the conversation and group lists. */
object ReadAll {
    /** Marks everything read off the main thread, clears every message
     *  alert (notification, repeat sound, cover card), then calls [done]
     *  on the main thread so the list can redraw. */
    fun run(context: Context, done: () -> Unit) {
        val app = context.applicationContext
        ConversationListCache.markAllRead()
        Thread {
            val threads = SmsRepository(app).markAllRead()
            threads.forEach { id ->
                val key = NotificationHelper.conversationKey(id, "")
                NotificationHelper.cancelForConversation(app, key)
                SoundNotificationHelper.acknowledge(app, key)
            }
            MessageEvents.changed(null)
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                Toast.makeText(app, "All messages marked as read", Toast.LENGTH_SHORT).show()
                done()
            }
        }.start()
    }
}
