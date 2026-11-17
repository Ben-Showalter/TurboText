package com.turbotext.app

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.Telephony
import android.util.Log

/**
 * At every start (not only after a restart or update): messages left
 * "Sending" or "Waiting" with nothing still looking after them — their
 * alarms were lost when the app was stopped — are shown as Failed, so
 * they can be resent instead of saying "Sending" forever. Anything from
 * the last [STUCK_AFTER_MS] may still be going through and is left alone.
 */
object StuckSends {
    private const val STUCK_AFTER_MS = 10 * 60_000L

    fun sweep(context: Context) {
        val before = System.currentTimeMillis() - STUCK_AFTER_MS
        val waiting = waitingUris(context)
        sweepTexts(context, before, waiting)
        sweepPictures(context, before)
    }

    private fun waitingUris(context: Context): Set<String> = try {
        SendRetry.waitingSmsUris(context)
    } catch (e: Exception) {
        emptySet()
    }

    private fun sweepTexts(context: Context, before: Long, waiting: Set<String>) {
        try {
            val stuck = ArrayList<Uri>()
            context.contentResolver.query(
                Telephony.Sms.CONTENT_URI, arrayOf(Telephony.Sms._ID, Telephony.Sms.TYPE),
                "(${Telephony.Sms.TYPE} = ${Telephony.Sms.MESSAGE_TYPE_OUTBOX} OR ${Telephony.Sms.TYPE} = ${Telephony.Sms.MESSAGE_TYPE_QUEUED})" +
                    " AND ${Telephony.Sms.DATE} < $before",
                null, null
            )?.use { c ->
                while (c.moveToNext()) {
                    val uri = Uri.withAppendedPath(Telephony.Sms.CONTENT_URI, c.getLong(0).toString())
                    // Waiting for service, and still in SendRetry's queue: fine.
                    if (c.getInt(1) == Telephony.Sms.MESSAGE_TYPE_QUEUED && uri.toString() in waiting) continue
                    // Just sent again from the queue: an old row, but not stuck.
                    if (SendRetry.resentWithin(uri.toString(), STUCK_AFTER_MS)) continue
                    stuck.add(uri)
                }
            }
            for (uri in stuck) {
                context.contentResolver.update(
                    uri, ContentValues().apply { put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_FAILED) }, null, null
                )
            }
            if (stuck.isNotEmpty()) {
                AppLog.event("Sms", "marked ${stuck.size} stuck text(s) as failed: ${stuck.joinToString { it.lastPathSegment ?: "?" }}")
                MessageEvents.changed(null)
                ProviderChangeTracker.bump()
            }
        } catch (e: Exception) {
            Log.w("TurboTextSend", "couldn't check for stuck texts", e)
        }
    }

    /** A picture message still "Sending" from before lost its retry and
     *  watchdog alarms (MmsSentReceiver). Ones waiting for service
     *  (SendRetry) aren't stuck. */
    private fun sweepPictures(context: Context, before: Long) {
        try {
            val waiting = SendRetry.waitingMmsIds(context)
            val notWaiting = if (waiting.isEmpty()) "" else " AND ${Telephony.Mms._ID} NOT IN (${waiting.joinToString(",")})"
            val n = context.contentResolver.update(
                Telephony.Mms.CONTENT_URI,
                ContentValues().apply { put(Telephony.Mms.MESSAGE_BOX, Telephony.Mms.MESSAGE_BOX_FAILED) },
                "${Telephony.Mms.MESSAGE_BOX} = ${Telephony.Mms.MESSAGE_BOX_OUTBOX} AND ${Telephony.Mms.DATE} < ${before / 1000}$notWaiting",
                null
            )
            if (n > 0) {
                AppLog.event("Mms", "marked $n stuck picture message(s) as failed")
                MessageEvents.changed(null)
                ProviderChangeTracker.bump()
            }
        } catch (e: Exception) {
            Log.w("TurboTextMms", "couldn't check for stuck picture messages", e)
        }
    }
}
