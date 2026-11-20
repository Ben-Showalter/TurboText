/*
 * Adapted from DPAD Messaging (https://github.com/jbriones95/DPAD-Messaging),
 * helpers/MmsTransmitter.kt.
 *
 * MIT License — Copyright (c) 2025 DPAD Messaging
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.turbotext.app

import android.app.PendingIntent
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Telephony
import android.telephony.SmsManager
import android.util.Log
import com.android.mms.dom.smil.parser.SmilXmlSerializer
import com.google.android.mms.ContentType
import com.google.android.mms.InvalidHeaderValueException
import com.google.android.mms.MMSPart
import com.google.android.mms.pdu_alt.CharacterSets
import com.google.android.mms.pdu_alt.EncodedStringValue
import com.google.android.mms.pdu_alt.GenericPdu
import com.google.android.mms.pdu_alt.PduBody
import com.google.android.mms.pdu_alt.PduComposer
import com.google.android.mms.pdu_alt.PduHeaders
import com.google.android.mms.pdu_alt.PduPart
import com.google.android.mms.pdu_alt.PduPersister
import com.google.android.mms.pdu_alt.SendReq
import com.google.android.mms.smil.SmilHelper
import com.google.android.mms.util_alt.SqliteWrapper
import com.klinker.android.send_message.SmsManagerFactory
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * Composes an MMS, saves it in the system outbox, then hands it to the
 * phone's own MMS service with [SmsManager.sendMultimediaMessage].
 *
 * The carrier's MMSC address, proxy and APN are all looked up by the
 * platform at send time — nothing here guesses at them.
 *
 * 1. Build a `multipart/related` SendReq with a leading SMIL part.
 * 2. Persist it to `content://mms/outbox` so the message shows as
 *    "Sending" straight away.
 * 3. Write the PDU to a cache file exposed through mmslib's MmsFileProvider.
 * 4. Send, with a PendingIntent keyed on the row id so two sends in flight
 *    can't cancel each other; [MmsSentReceiver] gets the result.
 *
 * Only works while TurboText is the default SMS app.
 */
object MmsTransmitter {

    private const val TAG = "TurboTextMms"

    private const val DEFAULT_EXPIRY_TIME = 7L * 24 * 60 * 60

    const val EXTRA_CONTENT_URI = "content_uri"
    const val EXTRA_FILE_PATH = "file_path"
    const val EXTRA_SUB_ID = "sub_id"
    const val EXTRA_GROUP = "group_mms"
    const val EXTRA_ATTEMPT = "attempt"

    private val MMS_OUTBOX: Uri = Uri.parse("content://mms/outbox")

    /**
     * Sends [parts] (plus [body] as a text part) to [recipients] as one
     * MMS. [groupMms] makes it a shared group conversation when there are
     * several recipients. Returns the saved `content://mms/<id>` row, or
     * null if it couldn't be sent.
     */
    fun send(
        context: Context,
        recipients: List<String>,
        body: String,
        parts: List<MMSPart>,
        groupMms: Boolean,
        subscriptionId: Int = com.klinker.android.send_message.Settings.DEFAULT_SUBSCRIPTION_ID
    ): Uri? {
        return try {
            sendInternal(context, recipients, body, parts, subscriptionId, groupMms)
        } catch (e: Exception) {
            Log.e(TAG, "MmsTransmitter: send failed", e)
            null
        }
    }

    private fun sendInternal(
        context: Context,
        recipients: List<String>,
        body: String,
        parts: List<MMSPart>,
        subscriptionId: Int,
        groupMms: Boolean
    ): Uri? {
        // Media first, text last — the order other phones expect.
        val ordered = ArrayList<MMSPart>(parts)
        if (body.isNotBlank()) {
            ordered.add(MMSPart().apply {
                name = "text"
                mimeType = "text/plain"
                data = body.toByteArray()
            })
        }

        val sendReq = buildPdu(context, subscriptionId, recipients, ordered)
        val persister = PduPersister.getPduPersister(context)

        val messageUri = persister.persist(sendReq, MMS_OUTBOX, true, groupMms, null, subscriptionId)
        AppLog.event(
            "Mms", "${messageUri.lastPathSegment}: saved to ${recipients.size} recipient(s), group=$groupMms, " +
                "parts=${ordered.joinToString { "${it.mimeType} ${it.data?.size ?: 0}B" }}"
        )

        try {
            SqliteWrapper.update(
                context,
                context.contentResolver,
                messageUri,
                ContentValues(1).apply { put(Telephony.Mms.MESSAGE_BOX, Telephony.Mms.MESSAGE_BOX_OUTBOX) },
                null,
                null
            )
        } catch (e: Exception) {
            // Saved straight into the outbox already; this only repeats it.
            Log.w(TAG, "MmsTransmitter: couldn't re-mark the outbox row", e)
        }

        val (messageId, resolvedSubId) = readRow(context, messageUri, subscriptionId)

        // Armed before anything else can go wrong: if the app dies before
        // the hand-off, this finds no file (or no result) and marks it
        // failed instead of leaving it on "Sending". Re-armed at hand-off.
        val pduFile = File(context.cacheDir, "send.${UUID.randomUUID()}.dat")
        MmsSentReceiver.schedule(
            context, MmsSentReceiver.ACTION_WATCHDOG, messageUri, pduFile.path, resolvedSubId, groupMms, 1,
            MmsSentReceiver.WATCHDOG_MS
        )

        // Compose from the persisted PDU so the bytes sent are exactly
        // what was saved.
        val persistedPdu: GenericPdu = try {
            persister.load(messageUri)
        } catch (e: Exception) {
            Log.e(TAG, "MmsTransmitter: unable to reload persisted PDU", e)
            AppLog.event("Mms", "${messageUri.lastPathSegment}: FAILED (couldn't read it back: ${e.javaClass.simpleName})")
            markFailed(context, messageUri)
            return null
        }

        // From here on the message is in the outbox ("Sending"). Anything
        // going wrong must not leave it there: it used to stay on "Sending"
        // until a restart marked it failed, and was never tried again.
        try {
            handOff(context, messageUri, persistedPdu, pduFile, messageId, resolvedSubId, groupMms)
        } catch (e: Exception) {
            Log.e(TAG, "MmsTransmitter: hand-off failed", e)
            AppLog.event("Mms", "${messageUri.lastPathSegment}: hand-off to the phone failed: ${e.javaClass.simpleName}: ${e.message}")
            if (!pduFile.exists() ||
                !MmsSentReceiver.scheduleRetry(context, messageUri, pduFile.path, resolvedSubId, groupMms, 2)
            ) {
                pduFile.delete()
                markFailed(context, messageUri)
                AppLog.event("Mms", "${messageUri.lastPathSegment}: FAILED (hand-off)")
            }
        }
        // The row itself shows Sending / Failed from here, so the screen
        // treats it as handed over either way.
        return messageUri
    }

    /** Writes the PDU to [pduFile] and gives it to the phone's MMS
     *  service; [pduFile] is kept for a retry if this throws. */
    private fun handOff(
        context: Context, messageUri: Uri, persistedPdu: GenericPdu, pduFile: File,
        messageId: Int, resolvedSubId: Int, groupMms: Boolean
    ) {
        val fileName = pduFile.name
        val contentUri = Uri.Builder()
            .authority(context.packageName + ".MmsFileProvider")
            .path(fileName)
            .scheme(ContentResolver.SCHEME_CONTENT)
            .build()

        FileOutputStream(pduFile).use { it.write(PduComposer(context, persistedPdu).make()) }

        val sentIntent = Intent(context, MmsSentReceiver::class.java).apply {
            data = messageUri
            putExtra(EXTRA_CONTENT_URI, messageUri.toString())
            putExtra(EXTRA_FILE_PATH, pduFile.path)
            // So a failed attempt can be sent again (MmsSentReceiver).
            putExtra(EXTRA_SUB_ID, resolvedSubId)
            putExtra(EXTRA_GROUP, groupMms)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_IMMUTABLE else 0
        val pendingIntent = PendingIntent.getBroadcast(context, messageId, sentIntent, flags)

        val configOverrides = configOverrides(context, groupMms)

        SmsManagerFactory.createSmsManager(resolvedSubId)
            .sendMultimediaMessage(context, contentUri, null, configOverrides, pendingIntent)

        AppLog.event(
            "Mms", "$messageId: handed to the phone (${pduFile.length()}B, sim $resolvedSubId, " +
                "reports ${if (SettingsHelper.isMmsDeliveryReports(context)) "on" else "off"}, ${describe(context, configOverrides)})"
        )
        MmsSentReceiver.schedule(
            context, MmsSentReceiver.ACTION_WATCHDOG, messageUri, pduFile.path, resolvedSubId, groupMms, 1,
            MmsSentReceiver.WATCHDOG_MS
        )
    }

    /**
     * Sends the already-composed PDU in [pduPath] again, for attempt
     * [attempt] of the message at [messageUri] (still in the outbox).
     * False when the file is gone.
     */
    fun resend(context: Context, messageUri: Uri, pduPath: String, subId: Int, groupMms: Boolean, attempt: Int): Boolean {
        val pduFile = File(pduPath)
        if (!pduFile.exists()) return false
        val contentUri = Uri.Builder()
            .authority(context.packageName + ".MmsFileProvider")
            .path(pduFile.name)
            .scheme(ContentResolver.SCHEME_CONTENT)
            .build()
        val sentIntent = Intent(context, MmsSentReceiver::class.java).apply {
            data = messageUri
            putExtra(EXTRA_CONTENT_URI, messageUri.toString())
            putExtra(EXTRA_FILE_PATH, pduFile.path)
            putExtra(EXTRA_SUB_ID, subId)
            putExtra(EXTRA_GROUP, groupMms)
            putExtra(EXTRA_ATTEMPT, attempt)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_IMMUTABLE else 0
        val requestCode = messageUri.lastPathSegment?.toIntOrNull() ?: 0
        val pendingIntent = PendingIntent.getBroadcast(context, requestCode, sentIntent, flags)
        val configOverrides = configOverrides(context, groupMms)
        SmsManagerFactory.createSmsManager(subId)
            .sendMultimediaMessage(context, contentUri, null, configOverrides, pendingIntent)
        AppLog.event("Mms", "${messageUri.lastPathSegment}: try $attempt handed to the phone")
        MmsSentReceiver.schedule(
            context, MmsSentReceiver.ACTION_WATCHDOG, messageUri, pduPath, subId, groupMms, attempt,
            MmsSentReceiver.WATCHDOG_MS
        )
        return true
    }

    /**
     * What the phone's MMS service is told for each send — as older
     * TurboText (the klinker library) did, and the October rewrite
     * dropped: the carrier's extra HTTP headers from mms_config for this
     * SIM (on Verizon's network: X-VzW-MDN and x-up-calling-line-id, the
     * sender's number, which its MMSC uses to know who's sending), and
     * the size limit. The phone fills in ##LINE1…## itself.
     */
    private fun configOverrides(context: Context, groupMms: Boolean) = Bundle().apply {
        putBoolean(SmsManager.MMS_CONFIG_GROUP_MMS_ENABLED, groupMms)
        try {
            val fromConfig = com.android.mms.MmsConfig.getHttpParams()?.trim()?.takeIf { it.isNotEmpty() }
            val params = fromConfig ?: verizonParams(context)
            params?.let { putString(SmsManager.MMS_CONFIG_HTTP_PARAMS, it) }
            // Never below what AttachmentPolicy already sized the message to.
            putInt(
                SmsManager.MMS_CONFIG_MAX_MESSAGE_SIZE,
                maxOf(com.android.mms.MmsConfig.getMaxMessageSize(), AttachmentPolicy.HARD_CAP_BYTES.toInt())
            )
        } catch (e: Exception) {
            Log.w(TAG, "MmsTransmitter: no mms_config values", e)
        }
    }

    /** On Verizon's network with no headers from mms_config (US Mobile):
     *  X-VzW-MDN with our own number, which Verizon's server reads the
     *  sender from. Null elsewhere, or when our number isn't known. */
    private fun verizonParams(context: Context): String? {
        val tm = context.getSystemService(android.telephony.TelephonyManager::class.java) ?: return null
        val verizon = try {
            VerizonNetwork.isVerizon(tm.simOperator, tm.networkOperator, tm.networkOperatorName)
        } catch (e: Exception) {
            false
        }
        if (!verizon) return null
        return OwnNumber.best(context)?.let { VerizonNetwork.httpParamsFor(it.first) }
    }

    /** For the log (last 4 digits of numbers only): the network, where
     *  our number came from, the headers sent, and the size limit. */
    private fun describe(context: Context, b: Bundle): String {
        val tm = context.getSystemService(android.telephony.TelephonyManager::class.java)
        val network = try {
            "sim ${tm?.simOperator ?: "?"}, net ${tm?.networkOperator ?: "?"} ${tm?.networkOperatorName ?: ""}".trim()
        } catch (e: Exception) {
            "network ?"
        }
        val own = OwnNumber.best(context)?.let { "from ${it.second} …${OwnNumber.last4(it.first)}" } ?: "from none"
        val headers = b.getString(SmsManager.MMS_CONFIG_HTTP_PARAMS)?.let {
            if (it.contains("VzW", ignoreCase = true)) "verizon headers yes" else "headers from mms_config"
        } ?: "headers none"
        return "$network, $own, $headers, max ${b.getInt(SmsManager.MMS_CONFIG_MAX_MESSAGE_SIZE, 0) / 1024}KB"
    }

    fun markFailed(context: Context, messageUri: Uri) {
        try {
            context.contentResolver.update(
                messageUri,
                ContentValues(1).apply { put(Telephony.Mms.MESSAGE_BOX, Telephony.Mms.MESSAGE_BOX_FAILED) },
                null, null
            )
        } catch (_: Exception) {
        }
    }

    /** Reads `_id` and the effective SIM subscription back from the row. */
    private fun readRow(context: Context, messageUri: Uri, fallbackSubId: Int): Pair<Int, Int> {
        var id = -1
        var subId = fallbackSubId
        try {
            context.contentResolver.query(
                messageUri, arrayOf(Telephony.Mms._ID, Telephony.Mms.SUBSCRIPTION_ID), null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    id = cursor.getInt(0)
                    // NULL here means "default SIM", not SIM 0.
                    if (!cursor.isNull(1)) {
                        val stored = cursor.getInt(1)
                        if (stored >= 0) subId = stored
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "MmsTransmitter: unable to read row metadata for $messageUri", e)
        }
        return id to subId
    }

    private fun buildPdu(
        context: Context,
        subscriptionId: Int,
        recipients: List<String>,
        parts: List<MMSPart>
    ): SendReq {
        val req = SendReq()

        // Our number as the sender, read the way Signal does. Without it
        // the message said "insert my address", and Verizon's server
        // refused it (0x84, sender address unresolved).
        val own = OwnNumber.best(context)
        if (own != null) req.setFrom(EncodedStringValue(VerizonNetwork.fromAddress(own.first)))
        else req.prepareFromAddress(context, "", subscriptionId)
        recipients.forEach { req.addTo(EncodedStringValue(it)) }
        req.setDate(System.currentTimeMillis() / 1000)

        val body = PduBody()
        var size = 0
        parts.forEach { size += addPart(body, it) }

        // Several carriers and handsets mis-handle slide layout without a
        // SMIL document, so one is always included.
        val smil = ByteArrayOutputStream()
        SmilXmlSerializer.serialize(SmilHelper.createSmilDocument(body), smil)
        body.addPart(0, PduPart().apply {
            setContentId("smil".toByteArray())
            setContentLocation("smil.xml".toByteArray())
            setContentType(ContentType.APP_SMIL.toByteArray())
            setData(smil.toByteArray())
        })

        req.setBody(body)
        req.setMessageSize(size.toLong())
        req.setMessageClass(PduHeaders.MESSAGE_CLASS_PERSONAL_STR.toByteArray())
        req.setExpiry(DEFAULT_EXPIRY_TIME)

        try {
            req.setPriority(PduHeaders.PRIORITY_NORMAL)
            // The carrier sends an m-delivery-ind back per recipient; mmslib's
            // PushReceiver saves it, and SmsRepository shows "Delivered".
            // Off unless chosen in Advanced settings: older TurboText and
            // DPAD never asked, and iPhone failures began after it was on.
            req.setDeliveryReport(
                if (SettingsHelper.isMmsDeliveryReports(context)) PduHeaders.VALUE_YES else PduHeaders.VALUE_NO
            )
            req.setReadReport(PduHeaders.VALUE_NO)
        } catch (e: InvalidHeaderValueException) {
            Log.w(TAG, "MmsTransmitter: invalid header value while building PDU", e)
        }

        return req
    }

    private fun addPart(body: PduBody, media: MMSPart): Int {
        val name = media.name.ifBlank { "attachment" }
        val part = PduPart().apply {
            if (media.mimeType.startsWith("text")) setCharset(CharacterSets.UTF_8)
            setContentType(media.mimeType.toByteArray())
            setContentLocation(name.toByteArray())
            val dot = name.lastIndexOf(".")
            setContentId((if (dot == -1) name else name.substring(0, dot)).toByteArray())
            setData(media.data)
        }
        body.addPart(part)
        return media.data?.size ?: 0
    }
}
