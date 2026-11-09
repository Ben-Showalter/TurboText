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
    companion object {
        private const val REPORT_TAG = "TurboTextMmsReport"
        private const val TAG = "TurboTextMms"
        const val ACTION_RETRY = "com.turbotext.app.MMS_RETRY"
        const val ACTION_WATCHDOG = "com.turbotext.app.MMS_WATCHDOG"

        /** How long after a hand-off with no answer from the phone's MMS
         *  service before the message is sent again (or marked failed).
         *  Pictures used to wait on "Sending" until a restart. */
        const val WATCHDOG_MS = 6 * 60 * 1000L

        /** Tries in all, the first send included. */
        private const val MAX_ATTEMPTS = 3

        /** Wait before the 2nd and 3rd try. */
        private val RETRY_DELAYS_MS = longArrayOf(20_000L, 90_000L)

        /** SmsManager results worth another try: no data connection,
         *  couldn't reach the carrier's server, a dropped transfer. Not a
         *  bad APN or configuration — those fail the same way every time. */
        private val TRANSIENT_RESULTS = setOf(
            SmsManager.MMS_ERROR_UNSPECIFIED,
            SmsManager.MMS_ERROR_UNABLE_CONNECT_MMS,
            SmsManager.MMS_ERROR_HTTP_FAILURE,
            SmsManager.MMS_ERROR_IO_ERROR,
            SmsManager.MMS_ERROR_RETRY,
            8 // MMS_ERROR_NO_DATA_NETWORK (API 30)
        )

        /** Wakes [MmsSentReceiver] with [action] for the message at [uri]
         *  after [delayMs]: a retry, or the no-answer watchdog. One alarm
         *  per message and action; a newer one replaces it. */
        fun schedule(
            context: Context, action: String, uri: Uri, filePath: String,
            subId: Int, group: Boolean, attempt: Int, delayMs: Long
        ): Boolean = try {
            val intent = Intent(context, MmsSentReceiver::class.java).apply {
                this.action = action
                data = uri
                putExtra(MmsTransmitter.EXTRA_CONTENT_URI, uri.toString())
                putExtra(MmsTransmitter.EXTRA_FILE_PATH, filePath)
                putExtra(MmsTransmitter.EXTRA_SUB_ID, subId)
                putExtra(MmsTransmitter.EXTRA_GROUP, group)
                putExtra(MmsTransmitter.EXTRA_ATTEMPT, attempt)
            }
            val pending = android.app.PendingIntent.getBroadcast(
                context, uri.lastPathSegment?.toIntOrNull() ?: 0, intent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            val am = context.getSystemService(android.app.AlarmManager::class.java)
            am?.setAndAllowWhileIdle(
                android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP,
                android.os.SystemClock.elapsedRealtime() + delayMs, pending
            )
            am != null
        } catch (e: Exception) {
            Log.w(TAG, "couldn't schedule $action for $uri", e)
            false
        }

        /** Schedules try [attempt] after its pause (20 s, then 90 s). */
        fun scheduleRetry(context: Context, uri: Uri, filePath: String, subId: Int, group: Boolean, attempt: Int): Boolean {
            val delay = RETRY_DELAYS_MS[(attempt - 2).coerceIn(0, RETRY_DELAYS_MS.size - 1)]
            val ok = schedule(context, ACTION_RETRY, uri, filePath, subId, group, attempt, delay)
            if (ok) AppLog.event("Mms", "${uri.lastPathSegment}: try $attempt of $MAX_ATTEMPTS in ${delay / 1000}s")
            return ok
        }

        /** m-send-conf Response-Status: 0x80 accepted, 0xC0–0xDF a
         *  temporary refusal, anything else the carrier won't take it. */
        private const val RESPONSE_OK = 0x80
        private fun isTransientResponse(status: Int) = status in 0xC0..0xDF
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_RETRY) {
            retry(context, intent)
            return
        }
        if (intent.action == ACTION_WATCHDOG) {
            watchdog(context, intent)
            return
        }
        val uri = intent.getStringExtra(MmsTransmitter.EXTRA_CONTENT_URI)?.let { Uri.parse(it) } ?: intent.data
        val attempt = intent.getIntExtra(MmsTransmitter.EXTRA_ATTEMPT, 1)
        val sent = resultCode == Activity.RESULT_OK
        val httpStatus = intent.getIntExtra(SmsManager.EXTRA_MMS_HTTP_STATUS, 0)
        AppLog.event(
            "Mms", "${uri?.lastPathSegment}: result try $attempt: resultCode=$resultCode http=$httpStatus"
        )

        val sendConf = if (sent) intent.getByteArrayExtra(SmsManager.EXTRA_MMS_DATA) else null
        // The phone's MMS service reports success once the carrier's server
        // answered at all — the answer itself can still be a refusal.
        val responseStatus = responseStatusOf(sendConf)
        val ok = sent && (responseStatus == null || responseStatus == RESPONSE_OK)
        if (sent && !ok) AppLog.event("Mms", "${uri?.lastPathSegment}: carrier refused, response status 0x${Integer.toHexString(responseStatus ?: 0)}")

        val filePath = intent.getStringExtra(MmsTransmitter.EXTRA_FILE_PATH)
        // No signal or no data: wait for service and send again then
        // (SendRetry), however many tries that takes.
        if (!sent && uri != null && filePath != null &&
            (resultCode == 8 /* MMS_ERROR_NO_DATA_NETWORK */ || !SendRetry.inService(context))
        ) {
            SendRetry.waitMms(
                context.applicationContext, uri, filePath,
                intent.getIntExtra(MmsTransmitter.EXTRA_SUB_ID, -1),
                intent.getBooleanExtra(MmsTransmitter.EXTRA_GROUP, false)
            )
            return
        }
        if (!ok && uri != null && filePath != null && attempt < MAX_ATTEMPTS) {
            val transient = if (sent) isTransientResponse(responseStatus ?: 0)
            else resultCode in TRANSIENT_RESULTS && !(httpStatus in 400..499 && httpStatus != 408 && httpStatus != 429)
            if (transient && scheduleRetry(
                    context, uri, filePath, intent.getIntExtra(MmsTransmitter.EXTRA_SUB_ID, -1),
                    intent.getBooleanExtra(MmsTransmitter.EXTRA_GROUP, false), attempt + 1
                )
            ) return
        }

        filePath?.let { File(it).delete() }
        if (uri == null) return

        val confId = if (ok) messageIdOf(sendConf) else null
        AppLog.event("Mms", "${uri.lastPathSegment}: ${if (ok) "SENT" else "FAILED"} after try $attempt")
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
                    // Told first; then only the newest messages are read into
                    // the cache (a long thread's whole history took seconds).
                    MessageEvents.changed(threadId)
                    MessageCache.refreshRecent(context, threadId)
                }
                ProviderChangeTracker.bump()
            } catch (e: Exception) {
                Log.w("TurboTextMms", "failed to record MMS send result", e)
            } finally {
                pending.finish()
            }
        }.start()
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
            val subId = intent.getIntExtra(MmsTransmitter.EXTRA_SUB_ID, -1)
            val group = intent.getBooleanExtra(MmsTransmitter.EXTRA_GROUP, false)
            if (filePath != null && File(filePath).exists() && attempt < MAX_ATTEMPTS &&
                scheduleRetry(context, uri, filePath, subId, group, attempt + 1)
            ) return
            giveUp(context, uri, filePath, "couldn't hand try $attempt to the phone")
        }
    }

    /** No answer from the phone's MMS service [WATCHDOG_MS] after a
     *  hand-off: send it again, or after the last try mark it failed —
     *  rather than leave it on "Sending" until the next restart. */
    private fun watchdog(context: Context, intent: Intent) {
        val uri = intent.getStringExtra(MmsTransmitter.EXTRA_CONTENT_URI)?.let { Uri.parse(it) } ?: return
        val filePath = intent.getStringExtra(MmsTransmitter.EXTRA_FILE_PATH)
        val attempt = intent.getIntExtra(MmsTransmitter.EXTRA_ATTEMPT, MAX_ATTEMPTS)
        val box = try {
            context.contentResolver.query(uri, arrayOf(Telephony.Mms.MESSAGE_BOX), null, null, null)
                ?.use { if (it.moveToFirst()) it.getInt(0) else null }
        } catch (e: Exception) {
            null
        }
        // Answered (sent, failed or deleted), or parked for no signal.
        if (box != Telephony.Mms.MESSAGE_BOX_OUTBOX || SendRetry.isWaiting(context, uri.toString())) return
        AppLog.event("Mms", "${uri.lastPathSegment}: no answer to try $attempt after ${WATCHDOG_MS / 60000} min")
        if (filePath != null && File(filePath).exists() && attempt < MAX_ATTEMPTS) {
            val resent = try {
                MmsTransmitter.resend(
                    context, uri, filePath,
                    intent.getIntExtra(MmsTransmitter.EXTRA_SUB_ID, -1),
                    intent.getBooleanExtra(MmsTransmitter.EXTRA_GROUP, false),
                    attempt + 1
                )
            } catch (e: Exception) {
                Log.w(TAG, "watchdog resend failed", e)
                false
            }
            if (resent) return
        }
        giveUp(context, uri, filePath, "no answer after try $attempt")
    }

    private fun giveUp(context: Context, uri: Uri, filePath: String?, why: String) {
        filePath?.let { File(it).delete() }
        MmsTransmitter.markFailed(context, uri)
        AppLog.event("Mms", "${uri.lastPathSegment}: FAILED ($why)")
        MessageEvents.changed(null)
        ProviderChangeTracker.bump()
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
