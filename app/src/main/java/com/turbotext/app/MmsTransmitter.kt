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
import com.google.android.mms.MmsException
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
        Log.i(TAG, "MmsTransmitter: persisted $messageUri group=$groupMms subId=$subscriptionId")

        SqliteWrapper.update(
            context,
            context.contentResolver,
            messageUri,
            ContentValues(1).apply { put(Telephony.Mms.MESSAGE_BOX, Telephony.Mms.MESSAGE_BOX_OUTBOX) },
            null,
            null
        )

        val (messageId, resolvedSubId) = readRow(context, messageUri, subscriptionId)

        // Compose from the persisted PDU so the bytes sent are exactly
        // what was saved.
        val persistedPdu: GenericPdu = try {
            persister.load(messageUri)
        } catch (e: MmsException) {
            Log.e(TAG, "MmsTransmitter: unable to reload persisted PDU", e)
            markFailed(context, messageUri)
            return null
        }

        val fileName = "send.${UUID.randomUUID()}.dat"
        val pduFile = File(context.cacheDir, fileName)
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
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_IMMUTABLE else 0
        val pendingIntent = PendingIntent.getBroadcast(context, messageId, sentIntent, flags)

        val configOverrides = Bundle().apply {
            putBoolean(SmsManager.MMS_CONFIG_GROUP_MMS_ENABLED, groupMms)
        }

        SmsManagerFactory.createSmsManager(resolvedSubId)
            .sendMultimediaMessage(context, contentUri, null, configOverrides, pendingIntent)

        Log.i(TAG, "MmsTransmitter: handed to platform uri=$messageUri id=$messageId subId=$resolvedSubId")
        return messageUri
    }

    private fun markFailed(context: Context, messageUri: Uri) {
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

        req.prepareFromAddress(context, "", subscriptionId)
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
            req.setDeliveryReport(PduHeaders.VALUE_NO)
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
