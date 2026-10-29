package com.turbotext.app

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Telephony
import android.telephony.SmsManager
import android.util.Log
import java.io.File

/**
 * Result of an MMS handed to the platform by [MmsTransmitter]: moves the
 * row from the outbox to Sent or Failed, saves the Message-ID the carrier
 * assigned, deletes the temporary PDU file, and refreshes the cached
 * thread so the bubble's status updates.
 *
 * The Message-ID is what delivery reports refer back to: mmslib only
 * keeps a report whose ID matches a sent message's `m_id`, and
 * SmsRepository matches them up the same way.
 */
class MmsSentReceiver : BroadcastReceiver() {
    private companion object {
        const val REPORT_TAG = "TurboTextMmsReport"
        const val TAG = "TurboTextMms"
        const val ACTION_RETRY = "com.turbotext.app.MMS_RETRY"

        /** Tries in all, the first send included. */
        const val MAX_ATTEMPTS = 3

        /** Wait before the 2nd and 3rd try. */
        val RETRY_DELAYS_MS = longArrayOf(20_000L, 90_000L)

        /** SmsManager results worth another try: no data connection,
         *  couldn't reach the carrier's server, a dropped transfer. Not a
         *  bad APN or configuration — those fail the same way every time. */
        val TRANSIENT_RESULTS = setOf(
            SmsManager.MMS_ERROR_UNSPECIFIED,
            SmsManager.MMS_ERROR_UNABLE_CONNECT_MMS,
            SmsManager.MMS_ERROR_HTTP_FAILURE,
            SmsManager.MMS_ERROR_IO_ERROR,
            SmsManager.MMS_ERROR_RETRY,
            8 // MMS_ERROR_NO_DATA_NETWORK (API 30)
        )

        /** m-send-conf Response-Status: 0x80 accepted, 0xC0–0xDF a
         *  temporary refusal, anything else the carrier won't take it. */
        const val RESPONSE_OK = 0x80
        fun isTransientResponse(status: Int) = status in 0xC0..0xDF
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_RETRY) {
            retry(context, intent)
            return
        }
        val uri = intent.getStringExtra(MmsTransmitter.EXTRA_CONTENT_URI)?.let { Uri.parse(it) } ?: intent.data
        val attempt = intent.getIntExtra(MmsTransmitter.EXTRA_ATTEMPT, 1)
        val sent = resultCode == Activity.RESULT_OK
        val httpStatus = intent.getIntExtra(SmsManager.EXTRA_MMS_HTTP_STATUS, 0)
        Log.i(TAG, "MMS send result for $uri (try $attempt): resultCode=$resultCode http=$httpStatus")

        val sendConf = if (sent) intent.getByteArrayExtra(SmsManager.EXTRA_MMS_DATA) else null
        // The phone's MMS service reports success once the carrier's server
        // answered at all — the answer itself can still be a refusal.
        val responseStatus = responseStatusOf(sendConf)
        val ok = sent && (responseStatus == null || responseStatus == RESPONSE_OK)
        if (sent && !ok) Log.w(TAG, "carrier refused MMS $uri: response status 0x${Integer.toHexString(responseStatus ?: 0)}")

        val filePath = intent.getStringExtra(MmsTransmitter.EXTRA_FILE_PATH)
        if (!ok && uri != null && filePath != null && attempt < MAX_ATTEMPTS) {
            val transient = if (sent) isTransientResponse(responseStatus ?: 0)
            else resultCode in TRANSIENT_RESULTS && !(httpStatus in 400..499 && httpStatus != 408 && httpStatus != 429)
            if (transient && scheduleRetry(context, intent, uri, filePath, attempt + 1)) return
        }

        filePath?.let { File(it).delete() }
        if (uri == null) return

        val confId = if (ok) messageIdOf(sendConf) else null
        Log.i(
            REPORT_TAG,
            "send result for ${uri.lastPathSegment}: ok=$ok, confirmation=${sendConf?.size ?: 0} bytes, " +
                "Message-ID=${com.android.mms.transaction.PushReceiver.idForLog(confId?.toByteArray())}"
        )

        val pending = goAsync()
        Thread {
            try {
                context.contentResolver.update(
                    uri,
                    ContentValues(2).apply {
                        put(
                            Telephony.Mms.MESSAGE_BOX,
                            if (ok) Telephony.Mms.MESSAGE_BOX_SENT else Telephony.Mms.MESSAGE_BOX_FAILED
                        )
                        confId?.let { put(Telephony.Mms.MESSAGE_ID, it) }
                    },
                    null, null
                )
                // A delivery report that beat this send confirmation was
                // kept by PushReceiver; now that the Message-ID is saved,
                // it can be matched.
                if (ok) com.android.mms.transaction.PushReceiver.retryPendingReports(context)
                val threadId = context.contentResolver.query(uri, arrayOf(Telephony.Mms.THREAD_ID), null, null, null)
                    ?.use { if (it.moveToFirst()) it.getLong(0) else null }
                if (threadId != null) {
                    MessageCache.put(threadId, SmsRepository(context).getMessages(threadId))
                    MessageEvents.changed(threadId)
                }
                ProviderChangeTracker.bump()
            } catch (e: Exception) {
                Log.w("TurboTextMms", "failed to record MMS send result", e)
            } finally {
                pending.finish()
            }
        }.start()
    }

    /** Sends again after a pause; the row stays "Sending" meanwhile.
     *  False if the alarm couldn't be set (then it's marked failed). */
    private fun scheduleRetry(context: Context, result: Intent, uri: Uri, filePath: String, attempt: Int): Boolean {
        return try {
            val retry = Intent(context, MmsSentReceiver::class.java).apply {
                action = ACTION_RETRY
                data = uri
                putExtra(MmsTransmitter.EXTRA_CONTENT_URI, uri.toString())
                putExtra(MmsTransmitter.EXTRA_FILE_PATH, filePath)
                putExtra(MmsTransmitter.EXTRA_SUB_ID, result.getIntExtra(MmsTransmitter.EXTRA_SUB_ID, -1))
                putExtra(MmsTransmitter.EXTRA_GROUP, result.getBooleanExtra(MmsTransmitter.EXTRA_GROUP, false))
                putExtra(MmsTransmitter.EXTRA_ATTEMPT, attempt)
            }
            val pending = android.app.PendingIntent.getBroadcast(
                context, uri.lastPathSegment?.toIntOrNull() ?: 0, retry,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            val delay = RETRY_DELAYS_MS[(attempt - 2).coerceIn(0, RETRY_DELAYS_MS.size - 1)]
            val am = context.getSystemService(android.app.AlarmManager::class.java) ?: return false
            am.setAndAllowWhileIdle(
                android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP,
                android.os.SystemClock.elapsedRealtime() + delay, pending
            )
            Log.i(TAG, "MMS $uri will be tried again in ${delay / 1000}s (try $attempt of $MAX_ATTEMPTS)")
            true
        } catch (e: Exception) {
            Log.w(TAG, "couldn't schedule an MMS retry", e)
            false
        }
    }

    private fun retry(context: Context, intent: Intent) {
        val uri = intent.getStringExtra(MmsTransmitter.EXTRA_CONTENT_URI)?.let { Uri.parse(it) } ?: return
        val filePath = intent.getStringExtra(MmsTransmitter.EXTRA_FILE_PATH)
        val attempt = intent.getIntExtra(MmsTransmitter.EXTRA_ATTEMPT, MAX_ATTEMPTS)
        // Deleted or already sent another way in the meantime: nothing to do.
        val box = try {
            context.contentResolver.query(uri, arrayOf(Telephony.Mms.MESSAGE_BOX), null, null, null)
                ?.use { if (it.moveToFirst()) it.getInt(0) else null }
        } catch (e: Exception) {
            null
        }
        if (box != Telephony.Mms.MESSAGE_BOX_OUTBOX) {
            filePath?.let { File(it).delete() }
            return
        }
        val resent = filePath != null && try {
            MmsTransmitter.resend(
                context, uri, filePath,
                intent.getIntExtra(MmsTransmitter.EXTRA_SUB_ID, -1),
                intent.getBooleanExtra(MmsTransmitter.EXTRA_GROUP, false),
                attempt
            )
        } catch (e: Exception) {
            Log.w(TAG, "MMS retry failed to start", e)
            false
        }
        if (!resent) {
            filePath?.let { File(it).delete() }
            MmsTransmitter.markFailed(context, uri)
            MessageEvents.changed(null)
        }
    }

    /** The Response-Status in the carrier's m-send-conf, or null when
     *  there's no confirmation to read. */
    private fun responseStatusOf(sendConf: ByteArray?): Int? {
        if (sendConf == null || sendConf.isEmpty()) return null
        return try {
            (com.google.android.mms.pdu_alt.PduParser(sendConf, true).parse()
                as? com.google.android.mms.pdu_alt.SendConf)?.responseStatus?.takeIf { it != 0 }
        } catch (e: Exception) {
            null
        }
    }

    /** The Message-ID from the carrier's m-send-conf, or null. */
    private fun messageIdOf(sendConf: ByteArray?): String? {
        if (sendConf == null || sendConf.isEmpty()) return null
        return try {
            val conf = com.google.android.mms.pdu_alt.PduParser(sendConf, true).parse()
                as? com.google.android.mms.pdu_alt.SendConf ?: return null
            conf.messageId?.let { String(it) }?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            Log.w("TurboTextMms", "couldn't read Message-ID from send-conf", e)
            null
        }
    }
}
