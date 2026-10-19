package com.turbotext.app

import android.content.Context
import android.util.Log

/**
 * Messages that are on their way out but may not have a row in the
 * provider yet. An MMS only gets one once its pictures are shrunk, and a
 * text sent from New Message lands in a thread that's only just opened,
 * so without these the bubble would appear late or not say "Sending".
 * ConversationActivity shows them under the real messages through
 * [merge]; each one disappears as soon as its real row shows up, or when
 * the send finishes.
 *
 * A failed one stays as a "Failed" bubble until the thread takes its
 * text back into the compose box ([takeFailed]).
 */
object PendingSends {
    private const val TAG = "TurboTextSend"

    private class Entry(
        val threadId: Long,
        var message: Message,
        val attachments: List<OutgoingAttachment>
    )

    /** Pending ids are negative so they never collide with a real row. */
    private var nextId = -1L
    private val entries = LinkedHashMap<Long, Entry>()

    /**
     * Sends [body]/[attachments] to [recipients] on a background thread,
     * showing a "Sending" bubble in [threadId] meanwhile. [onStatus] gets
     * progress text ("Compressing video… 40%"); [onDone] gets null on
     * success or the error to show. Both run on the main thread, and the
     * send carries on even if the screen that started it closes.
     */
    fun send(
        context: Context,
        threadId: Long,
        recipients: List<String>,
        body: String,
        attachments: List<OutgoingAttachment>,
        onStatus: (String) -> Unit = {},
        onDone: (String?) -> Unit = {}
    ) {
        val app = context.applicationContext
        val key = add(threadId, recipients, body, attachments)
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        Thread {
            val error = try {
                MessageSender.send(app, recipients, body, attachments) { status -> main.post { onStatus(status) } }
            } catch (e: Exception) {
                Log.e(TAG, "send failed", e)
                e.message ?: "Couldn't send"
            }
            finish(key, error)
            MessageEvents.changed(threadId)
            main.post { onDone(error) }
        }.start()
    }

    private fun add(threadId: Long, recipients: List<String>, body: String, attachments: List<OutgoingAttachment>): Long {
        val id = synchronized(this) {
            val id = nextId--
            val image = attachments.firstOrNull { it.isImage }?.uri?.toString()
            val message = Message(
                id = id,
                address = recipients.joinToString(","),
                body = body,
                date = System.currentTimeMillis(),
                isOutgoing = true,
                isMms = attachments.isNotEmpty() || recipients.size > 1,
                imageUri = image,
                imageUris = attachments.filter { it.isImage }.mapNotNull { it.uri?.toString() },
                videoUri = attachments.firstOrNull { it.isVideo }?.uri?.toString(),
                audioUri = attachments.firstOrNull { it.isAudio }?.uri?.toString(),
                sendStatus = "sending"
            )
            entries[id] = Entry(threadId, message, attachments)
            id
        }
        MessageEvents.changed(threadId)
        return id
    }

    /** A successful send's bubble is replaced by its real row; a failed
     *  one stays, marked failed, for [takeFailed]. */
    private fun finish(key: Long, error: String?) {
        synchronized(this) {
            val entry = entries[key] ?: return
            if (error == null) {
                entries.remove(key)
            } else {
                entry.message = entry.message.copy(sendStatus = "failed")
            }
        }
    }

    /** [real] plus any pending bubbles for [threadId] that don't have a
     *  real row yet, newest last. */
    fun merge(threadId: Long, real: List<Message>): List<Message> {
        val pending = synchronized(this) {
            entries.values.filter { it.threadId == threadId }.map { it.message }
        }
        if (pending.isEmpty()) return real
        val shown = pending.filter { p -> p.sendStatus == "failed" || real.none { matches(p, it) } }
        if (shown.isEmpty()) return real
        return (real + shown).sortedBy { it.date }
    }

    /** The provider's copy of a pending message: outgoing, same text,
     *  written no earlier than a little before the send began. */
    internal fun matches(pending: Message, real: Message): Boolean =
        real.isOutgoing && real.id >= 0 &&
            real.body.trim() == pending.body.trim() &&
            real.date >= pending.date - 60_000

    /** Removes [threadId]'s failed sends and hands back what they held,
     *  so the screen can put the text and attachments back to retry. */
    fun takeFailed(threadId: Long): List<Pair<String, List<OutgoingAttachment>>> {
        synchronized(this) {
            val failed = entries.filter { it.value.threadId == threadId && it.value.message.sendStatus == "failed" }
            failed.keys.forEach { entries.remove(it) }
            return failed.values.map { it.message.body to it.attachments }
        }
    }
}
