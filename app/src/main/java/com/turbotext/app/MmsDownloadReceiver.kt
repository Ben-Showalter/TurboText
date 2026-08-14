package com.turbotext.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.io.File

private const val TAG = "TurboTextMms"

/** Receives the callback once Android's MmsManager finishes (or fails)
 *  downloading an MMS's actual content, triggered from
 *  MmsDeliverReceiver. */
class MmsDownloadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val filePath = intent.getStringExtra(EXTRA_FILE_PATH)
        val address = intent.getStringExtra(EXTRA_ADDRESS) ?: "Unknown"
        val file = filePath?.let { File(it) }

        try {
            // Verified against AOSP's SmsManager.java: 4=MMS_ERROR_HTTP_FAILURE,
            // 5=MMS_ERROR_IO_ERROR, 6=MMS_ERROR_RETRY, 7=MMS_ERROR_CONFIGURATION_ERROR,
            // 8=MMS_ERROR_NO_DATA_NETWORK.
            val httpStatus = intent.getIntExtra("android.telephony.extra.MMS_HTTP_STATUS", -1)
            Log.i(TAG, "download completed, resultCode=$resultCode, httpStatus=$httpStatus")
            if (resultCode != android.app.Activity.RESULT_OK || file == null || !file.exists()) {
                Log.w(TAG, "MMS download failed or produced no file")
                val displayName = ContactHelper.lookupName(context, address) ?: address
                NotificationHelper.showIncoming(
                    context, address, displayName,
                    "A picture message arrived but couldn't be downloaded."
                )
                return
            }

            val bytes = file.readBytes()
            val extracted = MmsRetrieveParser.extract(bytes)
            val resolvedAddress = address.takeIf { it != "Unknown" } ?: extracted.senderAddress ?: "Unknown"
            Log.i(TAG, "extracted: text=\"${extracted.text}\", hasImage=${extracted.imageBytes != null}, hasVcard=${extracted.vcardBytes != null}, sender=$resolvedAddress, allAddresses=${extracted.allAddresses}")

            val threadId = SmsRepository(context).insertReceivedMms(
                resolvedAddress, extracted.text, extracted.imageBytes, extracted.vcardBytes, extracted.allAddresses
            )
            // Same immediate cache refresh as the SMS path — this thread
            // might not currently be open anywhere.
            if (threadId != null) {
                Thread {
                    try {
                        val fresh = SmsRepository(context).getMessages(threadId)
                        MessageCache.put(threadId, fresh)
                        val newest = fresh.maxByOrNull { it.date }
                        Log.i(TAG, "cache refreshed for threadId=$threadId, ${fresh.size} messages, newest hasImage=${newest?.imageUri != null}")
                    } catch (e: Exception) {
                        Log.w(TAG, "failed to refresh cache for new MMS", e)
                    }
                }.start()
            }

            val trueParticipants = if (threadId != null) SmsRepository(context).getThreadParticipants(threadId) else emptyList()
            val displayName = if (trueParticipants.size > 1) {
                GroupNicknameHelper.getNickname(context, threadId ?: -1)
                    ?: trueParticipants.joinToString(", ") { ContactHelper.lookupName(context, it) ?: it }
            } else {
                ContactHelper.lookupName(context, resolvedAddress) ?: resolvedAddress
            }
            val preview = when {
                extracted.text.isNotEmpty() -> extracted.text
                extracted.imageBytes != null -> "Picture message"
                else -> "Picture message (couldn't read contents)"
            }
            val groupAddress = if (trueParticipants.size > 1) trueParticipants.joinToString(",") else null
            NotificationHelper.showIncoming(
                context, resolvedAddress, displayName, preview,
                threadId = threadId, conversationAddress = groupAddress
            )
            // Same conversation-key address NotificationHelper.showIncoming
            // just used for its "address" extra above (groupAddress, when
            // this is a group thread) — has to match, or acknowledge()
            // later cancels the wrong id and the rich card/alarm never
            // clears for group MMS threads.
            SoundNotificationHelper.notifyNewMessage(context, groupAddress ?: resolvedAddress, displayName)
            ReadAloudHelper.maybeReadAloud(context, displayName, extracted.text)
        } catch (e: Exception) {
            Log.e(TAG, "error handling downloaded MMS", e)
        } finally {
            file?.delete()
        }
    }

    companion object {
        const val EXTRA_FILE_PATH = "file_path"
        const val EXTRA_ADDRESS = "address"
    }
}
