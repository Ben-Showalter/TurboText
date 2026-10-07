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
 * row from the outbox to Sent or Failed, deletes the temporary PDU file,
 * and refreshes the cached thread so the bubble's status updates.
 */
class MmsSentReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val uri = intent.getStringExtra(MmsTransmitter.EXTRA_CONTENT_URI)?.let { Uri.parse(it) } ?: intent.data
        val ok = resultCode == Activity.RESULT_OK
        val httpStatus = intent.getIntExtra(SmsManager.EXTRA_MMS_HTTP_STATUS, 0)
        Log.i("TurboTextMms", "MMS send result for $uri: resultCode=$resultCode http=$httpStatus")

        intent.getStringExtra(MmsTransmitter.EXTRA_FILE_PATH)?.let { File(it).delete() }
        if (uri == null) return

        val pending = goAsync()
        Thread {
            try {
                context.contentResolver.update(
                    uri,
                    ContentValues(1).apply {
                        put(
                            Telephony.Mms.MESSAGE_BOX,
                            if (ok) Telephony.Mms.MESSAGE_BOX_SENT else Telephony.Mms.MESSAGE_BOX_FAILED
                        )
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
}
