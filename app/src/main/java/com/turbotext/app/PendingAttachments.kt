package com.turbotext.app

import android.content.Context
import android.view.View
import android.widget.TextView
import android.widget.Toast

/**
 * What's attached to the message being composed, shared by the
 * conversation and new-message screens. Each pick adds to the list (up to
 * [MAX]) rather than replacing it, so several photos can go in one
 * message whether they're picked together or one at a time. Keeps the
 * screen's "attached" [indicator] in step.
 */
class PendingAttachments(private val context: Context, private val indicator: TextView) {

    companion object {
        /** Most attachments in one message. The carrier's size limit is
         *  split evenly between them (see MessageSender), so each one more
         *  means every photo is shrunk further. */
        const val MAX = 5

        /** Indicator text for [items], e.g. "📷 3 photos attached". */
        fun labelFor(items: List<OutgoingAttachment>): String = when {
            items.size == 1 -> items[0].label
            items.all { it.isImage } -> "📷 ${items.size} photos attached"
            else -> "📎 ${items.size} items attached"
        }
    }

    var items: List<OutgoingAttachment> = emptyList()
        private set

    fun isEmpty() = items.isEmpty()

    /** Adds [added], keeping at most [MAX] in all, and says so. */
    fun add(added: List<OutgoingAttachment>) {
        if (added.isEmpty()) return
        val taken = added.take((MAX - items.size).coerceAtLeast(0))
        items = items + taken
        refresh()
        val message = if (taken.size < added.size) {
            "Up to $MAX attachments per message"
        } else {
            "${labelFor(items)} — press Send"
        }
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    /** Removes everything ("Remove Attachment", or once it's sending). */
    fun clear() {
        items = emptyList()
        refresh()
    }

    /** Puts [previous] back after a failed send, unless something new
     *  was attached in the meantime. */
    fun restore(previous: List<OutgoingAttachment>) {
        if (items.isNotEmpty() || previous.isEmpty()) return
        items = previous
        refresh()
    }

    private fun refresh() {
        if (items.isEmpty()) {
            indicator.visibility = View.GONE
        } else {
            indicator.text = labelFor(items)
            indicator.visibility = View.VISIBLE
        }
    }
}
