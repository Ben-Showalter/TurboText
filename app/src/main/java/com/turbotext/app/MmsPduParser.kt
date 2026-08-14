package com.turbotext.app

import android.util.Log

private const val TAG = "TurboTextMms"

/** Verified against AOSP's own PduHeaders.java (Apache 2.0) — these are
 *  the real WAP/MMS binary header field codes, not guessed. */
private object MmsHeader {
    const val MESSAGE_TYPE = 0x8C
    const val TRANSACTION_ID = 0x98
    const val CONTENT_LOCATION = 0x83
}

/**
 * Minimal, defensive parser for an M-Notification.ind MMS PDU — just
 * enough to pull out the Content-Location URL needed to download the
 * actual message. This is genuinely one of the trickiest, least-tested
 * parts of building an SMS/MMS app from scratch (see README) — the field
 * codes are real, sourced from AOSP, but the parsing logic hasn't been
 * tested against actual carrier traffic, so it's wrapped defensively and
 * fails safely (returns null) rather than crashing on anything malformed
 * or unexpected.
 */
object MmsPduParser {

    data class NotificationInfo(val contentLocation: String, val transactionId: String?)

    fun parseNotificationIndication(data: ByteArray): NotificationInfo? {
        return try {
            parseInternal(data)
        } catch (e: Exception) {
            Log.w(TAG, "failed to parse MMS notification PDU", e)
            null
        }
    }

    private fun parseInternal(data: ByteArray): NotificationInfo? {
        if (data.isEmpty()) return null

        // A real captured hex dump (starting "8C 82 98 ...") confirmed
        // Android's own telephony stack has already stripped the WSP push
        // envelope (transaction id, PDU type, Content-Type header) before
        // handing us this byte array — it starts directly at the MMS
        // Message-Type field. The earlier version of this parser assumed
        // that envelope was still present and skipped into the middle of
        // real data, misaligning everything after it.
        var pos = 0

        var transactionId: String? = null
        var contentLocation: String? = null

        while (pos < data.size) {
            val fieldCode = data[pos].toInt() and 0xFF
            pos += 1
            if (pos > data.size) break

            when (fieldCode) {
                MmsHeader.MESSAGE_TYPE -> {
                    if (pos >= data.size) break
                    pos += 1 // single value octet
                }
                MmsHeader.TRANSACTION_ID -> {
                    val result = readTextString(data, pos) ?: break
                    transactionId = result.first
                    pos = result.second
                }
                MmsHeader.CONTENT_LOCATION -> {
                    val result = readTextString(data, pos) ?: break
                    contentLocation = result.first
                    pos = result.second
                }
                else -> {
                    pos = skipUnknownField(data, pos) ?: break
                }
            }
        }

        return contentLocation?.let { NotificationInfo(it, transactionId) }
    }

    private fun readTextString(data: ByteArray, pos: Int): Pair<String, Int>? {
        var end = pos
        while (end < data.size && data[end].toInt() != 0) end++
        if (end >= data.size) return null
        val text = String(data, pos, end - pos, Charsets.US_ASCII)
        return text to (end + 1)
    }

    /** Best-effort skip for a header field we don't specifically parse —
     *  handles the three general WSP value encodings: a short length byte
     *  (< 32) followed by that many bytes, a "length-quote" (31) followed
     *  by a variable-length integer and that many bytes, a single
     *  well-known value octet (128-255), or a plain null-terminated
     *  string (anything else). */
    private fun skipUnknownField(data: ByteArray, pos: Int): Int? {
        if (pos >= data.size) return null
        val first = data[pos].toInt() and 0xFF
        return when {
            first < 32 -> {
                val next = pos + 1 + first
                if (next > data.size) null else next
            }
            first == 31 -> {
                val lenResult = readUintvar(data, pos + 1) ?: return null
                val next = lenResult.second + lenResult.first
                if (next > data.size) null else next
            }
            first in 128..255 -> pos + 1
            else -> readTextString(data, pos)?.second
        }
    }

    private fun readUintvar(data: ByteArray, pos: Int): Pair<Int, Int>? {
        var value = 0
        var i = pos
        while (i < data.size) {
            val b = data[i].toInt() and 0xFF
            value = (value shl 7) or (b and 0x7F)
            i++
            if (b and 0x80 == 0) return value to i
        }
        return null
    }
}
