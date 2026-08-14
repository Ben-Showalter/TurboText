package com.turbotext.app

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.telephony.SmsMessage
import android.util.Log

/** Handles the carrier's delivery-confirmation callback — distinct from
 *  SmsSentReceiver, which only confirms the message left this phone.
 *  This confirms the recipient's phone actually received it, via the
 *  SMS provider's standard STATUS column.
 *
 *  Worth being upfront: delivery reports are carrier-dependent. Not
 *  every carrier sends this confirmation back, and some require it to
 *  be explicitly enabled on the account. If this never updates past
 *  "Sent", that's the most likely reason — not a bug in this code. */
class SmsDeliveredReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val rowUriString = intent.getStringExtra("row_uri") ?: return
        val threadId = intent.getLongExtra("thread_id", -1L)
        val rowUri = android.net.Uri.parse(rowUriString)

        // The delivery report's PDU tells us whether it was actually a
        // success or a failure — resultCode alone (unlike the sent
        // callback) isn't the whole story, since the broadcast itself
        // succeeding just means we received *a* report, not that it was
        // a positive one.
        val delivered = try {
            val pdu = intent.getByteArrayExtra("pdu")
            if (pdu != null) {
                val message = SmsMessage.createFromPdu(pdu)
                message?.status == 0 // 0 = successfully delivered, per the SMS PDU status spec
            } else {
                resultCode == Activity.RESULT_OK
            }
        } catch (e: Exception) {
            resultCode == Activity.RESULT_OK
        }

        val values = ContentValues().apply {
            put(
                Telephony.Sms.STATUS,
                if (delivered) Telephony.Sms.STATUS_COMPLETE else Telephony.Sms.STATUS_FAILED
            )
        }
        try {
            context.contentResolver.update(rowUri, values, null, null)
        } catch (e: Exception) {
            Log.w("TurboTextSend", "failed to update delivery status", e)
        }

        if (threadId >= 0) {
            Thread {
                try {
                    MessageCache.put(threadId, SmsRepository(context).getMessages(threadId))
                } catch (e: Exception) {
                    Log.w("TurboTextSend", "failed to refresh cache after delivery confirmation", e)
                }
            }.start()
        }
    }
}
