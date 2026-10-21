package com.turbotext.app

import android.content.Context
import android.net.Uri
import android.provider.Telephony
import android.util.Log
import java.util.Collections

/**
 * What happens after an incoming MMS is saved: refresh caches, work out
 * the conversation's display name (group-aware), notify, and play the
 * message sound. Modeled on DPAD Messaging's MmsReceiveWorker, minus
 * WorkManager — mmslib already calls us on a background thread.
 */
object MmsReceivePostProcessor {

    private const val TAG = "TurboTextMms"

    /** m-retrieve-conf: a fully downloaded MMS. */
    private const val M_TYPE_RETRIEVE_CONF = 132

    /** Message ids already handled, so the success and fallback paths
     *  can't both notify for the same message. */
    private val handled: MutableSet<Long> = Collections.synchronizedSet(HashSet())

    fun process(context: Context, messageUri: Uri) {
        val id = messageUri.lastPathSegment?.toLongOrNull() ?: return
        handle(context, id)
    }

    /** Handles every downloaded inbox MMS from the last 3 minutes that
     *  hasn't been yet. Only the newest used to be picked, so in a burst
     *  (a busy group chat) the others never notified. */
    fun processRecentFallback(context: Context) {
        try {
            val since = System.currentTimeMillis() / 1000 - 180
            val ids = context.contentResolver.query(
                Telephony.Mms.Inbox.CONTENT_URI,
                arrayOf(Telephony.Mms._ID),
                "${Telephony.Mms.MESSAGE_TYPE} = ? AND ${Telephony.Mms.DATE} >= ?",
                arrayOf(M_TYPE_RETRIEVE_CONF.toString(), since.toString()),
                "${Telephony.Mms.DATE} ASC"
            )?.use { c -> generateSequence { if (c.moveToNext()) c.getLong(0) else null }.toList() }.orEmpty()
            val fresh = ids.filter { it !in handled }
            if (fresh.isNotEmpty()) {
                fresh.forEach { handle(context, it) }
            } else if (ids.isEmpty()) {
                NotificationHelper.showMmsDownloadFailed(context)
            }
        } catch (e: Exception) {
            Log.w(TAG, "fallback scan failed", e)
        }
    }

    private fun handle(context: Context, id: Long) {
        if (!handled.add(id)) return
        try {
            // A group message may tell us our own number at last, and fix
            // which thread this (and earlier) messages belong in.
            OwnNumber.learnFrom(context, id)
            // The provider can lag a moment behind the library's insert
            // before the row has its thread id — retry briefly.
            var threadId: Long? = null
            repeat(3) { attempt ->
                if (threadId == null) {
                    threadId = threadIdOf(context, id)
                    if (threadId == null && attempt < 2) Thread.sleep(700)
                }
            }
            val sender = senderOf(context, id) ?: "Unknown"
            val repo = SmsRepository(context)
            // The carrier's notice for this message should be gone now;
            // make sure it doesn't linger as a "press OK to download" twin.
            repo.removeStaleMmsNotices()
            val preview = repo.mmsPreview(id)

            ProviderChangeTracker.bump()
            val tid = threadId
            if (tid != null) {
                try {
                    MessageCache.put(tid, repo.getMessages(tid))
                    MessageEvents.changed(tid)
                } catch (e: Exception) {
                    Log.w(TAG, "failed to refresh cache for new MMS", e)
                }
            }

            val participants = if (tid != null) repo.getThreadParticipants(tid) else emptyList()
            val isGroup = participants.size > 1
            val displayName = if (isGroup) {
                GroupNicknameHelper.getNickname(context, tid ?: -1)
                    ?: participants.joinToString(", ") { ContactHelper.lookupName(context, it) ?: it }
            } else {
                ContactHelper.lookupName(context, sender) ?: sender
            }
            // Keyed by thread (NotificationHelper.conversationKey), the same
            // key ConversationActivity clears with when the thread is opened.
            val groupAddress = if (isGroup) participants.joinToString(",") else null
            val resolvedThreadId = NotificationHelper.showIncoming(
                context, sender, displayName, preview,
                threadId = tid, conversationAddress = groupAddress
            )
            SoundNotificationHelper.notifyNewMessage(
                context, NotificationHelper.conversationKey(resolvedThreadId, groupAddress ?: sender), displayName,
                sender = sender, preview = preview
            )
            // An MMS came through, so an earlier "couldn't be downloaded"
            // notice is out of date (it was most likely this message).
            NotificationHelper.cancelMmsDownloadFailed(context)
            Log.i(TAG, "processed MMS id=$id thread=$tid from=$sender group=$isGroup")
            // Which numbers the message named and which we took for ours
            // (last 4 digits only), to see where a group message went.
            Log.i(
                TAG,
                "MMS $id addresses: from=${digits(context, id, "137")} to=${digits(context, id, "151")} " +
                    "cc=${digits(context, id, "130")}; thread $tid has ${participants.size} people " +
                    "(${participants.joinToString(" ") { OwnNumber.last4(it) }}); ours: ${OwnNumber.describe(context)}"
            )
        } catch (e: Exception) {
            Log.e(TAG, "error post-processing MMS id=$id", e)
        }
    }

    private fun digits(context: Context, id: Long, types: String): String =
        context.contentResolver.query(
            Uri.parse("content://mms/$id/addr"), arrayOf("address"), "type IN ($types)", null, null
        )?.use { c ->
            generateSequence { if (c.moveToNext()) c.getString(0) else null }
                .joinToString(",") { OwnNumber.last4(it) }
        }.orEmpty().ifEmpty { "-" }

    private fun threadIdOf(context: Context, id: Long): Long? =
        context.contentResolver.query(
            Uri.withAppendedPath(Telephony.Mms.CONTENT_URI, id.toString()),
            arrayOf(Telephony.Mms.THREAD_ID), null, null, null
        )?.use { if (it.moveToFirst() && !it.isNull(0)) it.getLong(0).takeIf { t -> t > 0 } else null }

    /** FROM address — type 137 in the part's addr table (PduHeaders.FROM). */
    private fun senderOf(context: Context, id: Long): String? =
        context.contentResolver.query(
            Uri.parse("content://mms/$id/addr"), arrayOf("address"), "type = 137", null, null
        )?.use { if (it.moveToFirst()) it.getString(0) else null }
            ?.takeIf { it.isNotBlank() && it != "insert-address-token" }
}
