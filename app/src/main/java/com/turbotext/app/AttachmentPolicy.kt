package com.turbotext.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.telephony.SmsManager
import android.util.Log
import com.google.android.mms.MMSPart
import java.io.ByteArrayOutputStream

/**
 * Turns an [OutgoingAttachment] into MMS parts that fit the carrier's
 * size limit.
 *
 * - Pictures: decoded at a reduced size, turned upright from their EXIF
 *   orientation, then JPEG-compressed down until they fit.
 * - Video: re-encoded by [VideoCompressor].
 * - Audio, contact cards and other files: sent as-is if they fit,
 *   otherwise refused with a message (re-encoding arbitrary files isn't
 *   possible).
 *
 * The size budget follows AOSP Messaging's approach (MmsUtils): the
 * carrier's maximum message size minus room for the text and headers.
 */
object AttachmentPolicy {

    private const val TAG = "TurboTextAttach"

    /** Used when the carrier config doesn't say. 600 KB gets through
     *  every US carrier we know of, sender and recipient side. */
    private const val DEFAULT_MAX_MESSAGE_BYTES = 600 * 1024

    /** Even if a carrier allows more, the *recipient's* carrier may not —
     *  messages over ~1 MB are often accepted, reported "sent", and then
     *  silently dropped in transit. */
    private const val HARD_CAP_BYTES = 1000 * 1024

    /** SMIL, headers, part names — what the PDU costs on top of the media. */
    private const val PDU_OVERHEAD_BYTES = 2 * 1024

    private const val MAX_IMAGE_SIDE = 1600

    class TooLargeException(message: String) : Exception(message)

    /** Largest whole MMS the carrier accepts, capped at [HARD_CAP_BYTES]. */
    fun maxMessageBytes(): Int {
        val fromCarrier = try {
            SmsManager.getDefault().carrierConfigValues
                .getInt(SmsManager.MMS_CONFIG_MAX_MESSAGE_SIZE, DEFAULT_MAX_MESSAGE_BYTES)
        } catch (e: Exception) {
            DEFAULT_MAX_MESSAGE_BYTES
        }
        return (if (fromCarrier > 0) fromCarrier else DEFAULT_MAX_MESSAGE_BYTES).coerceAtMost(HARD_CAP_BYTES)
    }

    /** Bytes left for the attachment once [bodyText] and headers are counted. */
    fun budgetFor(bodyText: String): Int =
        maxMessageBytes() - PDU_OVERHEAD_BYTES - bodyText.toByteArray().size

    /**
     * Reads, resizes or re-encodes [attachment] to fit [budget] bytes.
     * Blocks (video compression can take a while) — call off the main
     * thread. [onProgress] reports 0–100 while a video is compressed.
     */
    fun prepare(
        context: Context, attachment: OutgoingAttachment, budget: Int, onProgress: (Int) -> Unit = {}
    ): MMSPart {
        return when {
            attachment.isImage && attachment.mime != "image/gif" -> imagePart(context, attachment, budget)
            attachment.isVideo -> videoPart(context, attachment, budget, onProgress)
            else -> rawPart(context, attachment, budget)
        }
    }

    private fun part(name: String, mime: String, data: ByteArray) = MMSPart().apply {
        this.name = name
        this.mimeType = mime
        this.data = data
    }

    private fun readBytes(context: Context, attachment: OutgoingAttachment): ByteArray =
        attachment.bytes
            ?: attachment.uri?.let { uri -> context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }
            ?: throw TooLargeException("Couldn't read the attachment")

    private fun rawPart(context: Context, attachment: OutgoingAttachment, budget: Int): MMSPart {
        val data = readBytes(context, attachment)
        if (data.size > budget) {
            throw TooLargeException(
                "${attachment.name} is ${data.size / 1024} KB — too big for a picture message (limit ${budget / 1024} KB)"
            )
        }
        // Voice memos are recorded as AAC in an MP4 container; "audio/mp4"
        // is what other phones recognise for that.
        val mime = if (attachment.mime == "audio/x-m4a" || attachment.mime == "audio/m4a") "audio/mp4" else attachment.mime
        return part(attachment.name, mime, data)
    }

    private fun videoPart(
        context: Context, attachment: OutgoingAttachment, budget: Int, onProgress: (Int) -> Unit
    ): MMSPart {
        val uri = attachment.uri ?: throw TooLargeException("Couldn't read the video")
        // Small enough already (e.g. a short clip recorded at low quality)
        // — send it untouched rather than lose quality re-encoding.
        val size = sizeOf(context, uri)
        if (size in 1..budget.toLong()) return rawPart(context, attachment, budget)

        val file = try {
            VideoCompressor.compress(context, uri, budget, onProgress)
        } catch (e: VideoCompressor.TooLargeException) {
            throw TooLargeException(e.message ?: "Video too large")
        }
        return try {
            part("video_${System.currentTimeMillis()}.mp4", "video/mp4", file.readBytes())
        } finally {
            file.delete()
        }
    }

    private fun sizeOf(context: Context, uri: android.net.Uri): Long = try {
        context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
    } catch (e: Exception) {
        -1L
    }

    private fun imagePart(context: Context, attachment: OutgoingAttachment, budget: Int): MMSPart {
        val uri = attachment.uri
        if (uri == null) return rawPart(context, attachment, budget)

        var bitmap = ThumbnailLoader.decodeSampled(context, uri, MAX_IMAGE_SIDE)
            ?: throw TooLargeException("Couldn't read that picture")
        bitmap = rotateUpright(context, uri, bitmap)

        // Shrink quality first, then dimensions — each pass measured on the
        // real compressed size rather than estimated.
        var quality = 85
        var data = jpeg(bitmap, quality)
        var passes = 0
        while (data.size > budget && passes < 8) {
            if (quality > 60) {
                quality -= 10
            } else {
                val scale = kotlin.math.sqrt(budget.toDouble() / data.size) * 0.9
                val w = (bitmap.width * scale).toInt().coerceAtLeast(64)
                val h = (bitmap.height * scale).toInt().coerceAtLeast(64)
                val scaled = Bitmap.createScaledBitmap(bitmap, w, h, true)
                if (scaled !== bitmap) bitmap.recycle()
                bitmap = scaled
            }
            data = jpeg(bitmap, quality)
            passes++
        }
        bitmap.recycle()
        if (data.size > budget) throw TooLargeException("Couldn't shrink the picture enough to send")
        Log.i(TAG, "picture ready: ${data.size}B at q$quality (budget ${budget}B)")
        return part("image_${System.currentTimeMillis()}.jpg", "image/jpeg", data)
    }

    private fun jpeg(bitmap: Bitmap, quality: Int): ByteArray {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        return out.toByteArray()
    }

    /** Phone cameras save pictures sideways and record the turn in EXIF;
     *  re-encoding drops that tag, so apply it to the pixels first. */
    private fun rotateUpright(context: Context, uri: android.net.Uri, bitmap: Bitmap): Bitmap {
        val degrees = try {
            context.contentResolver.openInputStream(uri)?.use {
                when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } ?: 0f
        } catch (e: Exception) {
            0f
        }
        if (degrees == 0f) return bitmap
        val m = Matrix().apply { postRotate(degrees) }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
        if (rotated !== bitmap) bitmap.recycle()
        return rotated
    }
}
