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
        // a positive one. The PDU has to be read in the format it came in
        // (GSM or CDMA), which the broadcast says.
        val result = try {
            val pdu = intent.getByteArrayExtra("pdu")
            val format = intent.getStringExtra("format")
            val message = when {
                pdu == null -> null
                format != null -> SmsMessage.createFromPdu(pdu, format)
                else -> SmsMessage.createFromPdu(pdu)
            }
            if (message != null) {
                Log.i(
                    "TurboTextMmsReport",
                    "text delivery report for ${rowUri.lastPathSegment}: status=0x" +
                        Integer.toHexString(message.status) + " format=$format"
                )
                DeliveryStatus.classify(message.status, format == "3gpp2")
            } else if (resultCode == Activity.RESULT_OK) {
                DeliveryStatus.Result.DELIVERED
            } else {
                DeliveryStatus.Result.FAILED
            }
        } catch (e: Exception) {
            if (resultCode == Activity.RESULT_OK) DeliveryStatus.Result.DELIVERED else DeliveryStatus.Result.FAILED
        }

        Log.i("TurboTextMmsReport", "text ${rowUri.lastPathSegment} delivery: $result")
        val values = ContentValues().apply {
            put(
                Telephony.Sms.STATUS,
                when (result) {
                    DeliveryStatus.Result.DELIVERED -> Telephony.Sms.STATUS_COMPLETE
                    DeliveryStatus.Result.PENDING -> Telephony.Sms.STATUS_PENDING
                    DeliveryStatus.Result.FAILED -> Telephony.Sms.STATUS_FAILED
                }
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
                    MessageEvents.changed(threadId)
                } catch (e: Exception) {
                    Log.w("TurboTextSend", "failed to refresh cache after delivery confirmation", e)
                }
            }.start()
        }
    }
}
