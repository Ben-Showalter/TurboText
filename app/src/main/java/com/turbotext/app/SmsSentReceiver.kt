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

    companion object {
        const val EXTRA_PART_INDEX = "part_index"
        const val EXTRA_PART_COUNT = "part_count"

        /** row URI -> parts not yet reported. A long text goes out as
         *  several SMS parts, each reporting separately. */
        private val pending = HashMap<String, Int>()
        private val failed = HashSet<String>()

        fun expectParts(rowUri: String, count: Int) {
            synchronized(pending) {
                pending[rowUri] = count
                failed.remove(rowUri)
            }
        }

        /** Returns the row's final TYPE once it's decided, or null while
         *  parts are still outstanding. Any failed part fails the message
         *  straight away. If the process restarted mid-send (tracker
         *  empty), the last part's result decides. */
        private fun partResult(rowUri: String, index: Int, count: Int, ok: Boolean): Int? {
            synchronized(pending) { return decide(rowUri, index, count, ok) }
        }

        private fun decide(rowUri: String, index: Int, count: Int, ok: Boolean): Int? {
            if (!ok) {
                failed.add(rowUri)
                pending.remove(rowUri)
                return Telephony.Sms.MESSAGE_TYPE_FAILED
            }
            if (failed.contains(rowUri)) return null // already marked failed
            val remaining = pending[rowUri]
            if (remaining == null) {
                return if (index >= count - 1) Telephony.Sms.MESSAGE_TYPE_SENT else null
            }
            return if (remaining <= 1) {
                pending.remove(rowUri)
                Telephony.Sms.MESSAGE_TYPE_SENT
            } else {
                pending[rowUri] = remaining - 1
                null
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        val rowUriString = intent.getStringExtra("row_uri") ?: return
        val threadId = intent.getLongExtra("thread_id", -1L)
        val rowUri = android.net.Uri.parse(rowUriString)
        val partIndex = intent.getIntExtra(EXTRA_PART_INDEX, 0)
        val partCount = intent.getIntExtra(EXTRA_PART_COUNT, 1)

        val newType = partResult(rowUriString, partIndex, partCount, resultCode == Activity.RESULT_OK)
            ?: return // more parts still to report
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
