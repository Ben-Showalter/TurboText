package com.turbotext.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.SmsManager
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File

private const val TAG = "TurboTextMms"

/**
 * Fires when a picture message (MMS) notification arrives — parses it to
 * find the download URL, then asks Android's own MmsManager to fetch the
 * actual content. See MmsPduParser and MmsRetrieveParser for the honest
 * caveats on this — real MMS parsing, built from verified AOSP field
 * codes but untested against live carrier traffic. If this doesn't work
 * reliably, it can be reverted to the "notify only" placeholder it
 * replaced — ask if you want that.
 */
class MmsDeliverReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val data = intent.getByteArrayExtra("data")
        if (data == null) {
            Log.w(TAG, "WAP_PUSH_DELIVER had no data payload")
            NotificationHelper.showIncoming(context, "Picture message", "Picture message", "A picture message arrived.")
            return
        }

        val info = MmsPduParser.parseNotificationIndication(data)
        if (info == null) {
            Log.w(TAG, "couldn't parse MMS notification PDU, ${data.size} bytes: " +
                data.joinToString(" ") { "%02X".format(it) })
            NotificationHelper.showIncoming(
                context, "Picture message", "Picture message",
                "A picture message arrived but couldn't be processed."
            )
            return
        }

        try {
            val dir = File(context.cacheDir, "mms_downloads").apply { mkdirs() }
            val file = File(dir, "mms_${System.currentTimeMillis()}.dat")
            val fileUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            context.grantUriPermission("com.android.phone", fileUri,
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)

            val downloadedIntent = Intent(context, MmsDownloadReceiver::class.java).apply {
                putExtra(MmsDownloadReceiver.EXTRA_FILE_PATH, file.absolutePath)
                putExtra(MmsDownloadReceiver.EXTRA_ADDRESS, "Unknown")
            }
            val pendingIntent = android.app.PendingIntent.getBroadcast(
                context, file.absolutePath.hashCode(), downloadedIntent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_MUTABLE
            )

            val smsManager = SmsManager.getDefault()
            // Confirmed via a real test: this carrier's MMSC sends a
            // Content-Location URL ending in "message-id=" with nothing
            // after the "=" — verified directly in the raw notification
            // bytes, not a parsing bug. The Transaction-ID field (parsed
            // separately, successfully) is meant to be appended by the
            // client in that case — a known MMS carrier convention.
            val downloadUrl = if (info.contentLocation.endsWith("=") && !info.transactionId.isNullOrEmpty()) {
                info.contentLocation + info.transactionId
            } else {
                info.contentLocation
            }
            smsManager.downloadMultimediaMessage(
                context, downloadUrl, fileUri, null, pendingIntent
            )
            Log.i(TAG, "download started for $downloadUrl")
        } catch (e: Exception) {
            Log.e(TAG, "failed to start MMS download", e)
            NotificationHelper.showIncoming(
                context, "Picture message", "Picture message",
                "A picture message arrived but couldn't be downloaded."
            )
        }
    }
}
