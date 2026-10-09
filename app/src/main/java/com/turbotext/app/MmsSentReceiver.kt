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
    override fun onReceive(context: Context, intent: Intent) {
        val uri = intent.getStringExtra(MmsTransmitter.EXTRA_CONTENT_URI)?.let { Uri.parse(it) } ?: intent.data
        val ok = resultCode == Activity.RESULT_OK
        val httpStatus = intent.getIntExtra(SmsManager.EXTRA_MMS_HTTP_STATUS, 0)
        Log.i("TurboTextMms", "MMS send result for $uri: resultCode=$resultCode http=$httpStatus")

        intent.getStringExtra(MmsTransmitter.EXTRA_FILE_PATH)?.let { File(it).delete() }
        if (uri == null) return

        val sendConf = if (ok) intent.getByteArrayExtra(SmsManager.EXTRA_MMS_DATA) else null

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
                        messageIdOf(sendConf)?.let { put(Telephony.Mms.MESSAGE_ID, it) }
                    },
                    null, null
                )
                val threadId = context.contentResolver.query(uri, arrayOf(Telephony.Mms.THREAD_ID), null, null, null)
                    ?.use { if (it.moveToFirst()) it.getLong(0) else null }
                if (threadId != null) {
                    MessageCache.put(threadId, SmsRepository(context).getMessages(threadId))
                }
                ProviderChangeTracker.bump()
            } catch (e: Exception) {
                Log.w("TurboTextMms", "failed to record MMS send result", e)
            } finally {
                pending.finish()
            }
        }.start()
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
