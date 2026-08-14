package com.turbotext.app

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log

/** Handles SmsManager's sent-confirmation callback — updates the
 *  message's row from OUTBOX to SENT or FAILED based on the actual
 *  result (not just "we handed it to the radio"), and refreshes the
 *  cache so the status updates immediately without needing to reopen
 *  the thread. */
class SmsSentReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val rowUriString = intent.getStringExtra("row_uri") ?: return
        val threadId = intent.getLongExtra("thread_id", -1L)
        val rowUri = android.net.Uri.parse(rowUriString)

        val newType = if (resultCode == Activity.RESULT_OK) {
            Telephony.Sms.MESSAGE_TYPE_SENT
        } else {
            Telephony.Sms.MESSAGE_TYPE_FAILED
        }
        val values = ContentValues().apply { put(Telephony.Sms.TYPE, newType) }
        try {
            context.contentResolver.update(rowUri, values, null, null)
        } catch (e: Exception) {
            Log.w("TurboTextSend", "failed to update sent status", e)
        }

        if (threadId >= 0) {
            Thread {
                try {
                    MessageCache.put(threadId, SmsRepository(context).getMessages(threadId))
                } catch (e: Exception) {
                    Log.w("TurboTextSend", "failed to refresh cache after send confirmation", e)
                }
            }.start()
        }
    }
}
