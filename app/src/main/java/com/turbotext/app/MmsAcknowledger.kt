package com.turbotext.app

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.telephony.SmsManager
import android.util.Log
import com.google.android.mms.pdu_alt.AcknowledgeInd
import com.google.android.mms.pdu_alt.GenericPdu
import com.google.android.mms.pdu_alt.NotifyRespInd
import com.google.android.mms.pdu_alt.PduComposer
import com.google.android.mms.pdu_alt.PduHeaders
import com.google.android.mms.pdu_alt.PduParser
import com.google.android.mms.pdu_alt.RetrieveConf
import com.klinker.android.send_message.SmsManagerFactory
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * Tells the carrier a picture message was downloaded. The carrier only
 * sends the sender's "Delivered" report once the receiving phone confirms
 * the download, and some carriers send an unconfirmed message again.
 * TurboText never confirmed (mmslib only does it when given the carrier's
 * MMS server address directly), so nobody's pictures to a TurboText phone
 * ever showed Delivered.
 *
 * Sent the way the stock messaging app does it: an M-Acknowledge.ind (or,
 * when the message carries no transaction ID to acknowledge, an
 * M-NotifyResp.ind saying "retrieved") handed to the phone's own MMS
 * service, which already knows the carrier's server and network route.
 */
object MmsAcknowledger {
    private const val TAG = "TurboTextMmsReport"

    /** [retrieveConf] is the downloaded message as it came from the
     *  carrier; [notificationTransactionId] the notice's transaction ID;
     *  [contentLocation] where it was downloaded from. Background thread. */
    fun acknowledge(
        context: Context,
        retrieveConf: ByteArray,
        notificationTransactionId: String?,
        contentLocation: String?,
        subscriptionId: Int
    ) {
        try {
            val conf = PduParser(retrieveConf).parse() as? RetrieveConf
            val pdu = buildConfirmation(conf?.transactionId, notificationTransactionId?.toByteArray()) ?: run {
                Log.w(TAG, "download not confirmed: no transaction ID")
                return
            }
            val bytes = PduComposer(context, pdu).make() ?: return

            val fileName = "ack.${UUID.randomUUID()}.dat"
            val file = File(context.cacheDir, fileName)
            FileOutputStream(file).use { it.write(bytes) }
            val contentUri = Uri.Builder()
                .authority(context.packageName + ".MmsFileProvider")
                .path(fileName)
                .scheme(ContentResolver.SCHEME_CONTENT)
                .build()

            val sentIntent = Intent(context, MmsAckSentReceiver::class.java)
                .putExtra(MmsAckSentReceiver.EXTRA_FILE_PATH, file.path)
            val flags = PendingIntent.FLAG_ONE_SHOT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_IMMUTABLE else 0
            val pending = PendingIntent.getBroadcast(context, fileName.hashCode(), sentIntent, flags)

            // Some carriers want the confirmation sent to the address the
            // message was downloaded from; otherwise the MMS service uses
            // the carrier's own server.
            val locationUrl = if (com.android.mms.MmsConfig.getNotifyWapMMSC()) contentLocation else null
            SmsManagerFactory.createSmsManager(subscriptionId)
                .sendMultimediaMessage(context, contentUri, locationUrl, null, pending)
            Log.i(TAG, "download confirmation sent (${if (pdu is AcknowledgeInd) "acknowledge" else "notify-response"})")
        } catch (e: Exception) {
            Log.w(TAG, "couldn't confirm download to carrier", e)
        }
    }

    /** M-Acknowledge.ind when the downloaded message has a transaction
     *  ID; otherwise M-NotifyResp.ind ("retrieved") for the notice's ID;
     *  null when there's neither. */
    fun buildConfirmation(retrieveTransactionId: ByteArray?, notificationTransactionId: ByteArray?): GenericPdu? = when {
        retrieveTransactionId != null && retrieveTransactionId.isNotEmpty() ->
            AcknowledgeInd(PduHeaders.CURRENT_MMS_VERSION, retrieveTransactionId)
        notificationTransactionId != null && notificationTransactionId.isNotEmpty() ->
            NotifyRespInd(PduHeaders.CURRENT_MMS_VERSION, notificationTransactionId, PduHeaders.STATUS_RETRIEVED)
        else -> null
    }
}

/** Result of a download confirmation: logs it and removes the temp file. */
class MmsAckSentReceiver : BroadcastReceiver() {
    companion object {
        const val EXTRA_FILE_PATH = "file_path"
    }

    override fun onReceive(context: Context, intent: Intent) {
        intent.getStringExtra(EXTRA_FILE_PATH)?.let { File(it).delete() }
        val http = intent.getIntExtra(SmsManager.EXTRA_MMS_HTTP_STATUS, 0)
        Log.i(
            "TurboTextMmsReport",
            "download confirmed to carrier: " + (if (resultCode == Activity.RESULT_OK) "ok" else "failed ($resultCode)") +
                " http=$http"
        )
    }
}
