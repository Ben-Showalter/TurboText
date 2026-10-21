package com.turbotext.app

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Codec
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Re-encodes a video small enough to send as an MMS.
 *
 * Carriers cap a whole MMS at roughly 300 KB–1 MB, and a phone-recorded
 * clip is many times that. None of the open-source messaging apps
 * surveyed for this (AOSP Messaging, QUIK, Fossify Messages) actually
 * transcode video — they just refuse files over the limit — so this does
 * it with AndroidX Media3 Transformer, using the phone's own hardware
 * encoder: H.264 video + mono AAC audio in an MP4.
 *
 * The video bitrate is worked out from the size budget and the clip's
 * length. If the result still comes out too big, it retries smaller
 * (lower resolution and bitrate), and clips that are too long to fit at
 * any usable quality are trimmed.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
object VideoCompressor {

    private const val TAG = "TurboTextVideo"

    private const val AUDIO_BITRATE = 32_000
    /** Below this the picture turns to mush, so trim instead. */
    private const val MIN_VIDEO_BITRATE = 80_000
    private const val MAX_VIDEO_BITRATE = 600_000

    class TooLargeException(message: String) : Exception(message)

    /** One attempt's settings. */
    private data class Step(val height: Int, val bitrateScale: Float)

    private val STEPS = listOf(Step(240, 1.0f), Step(176, 0.75f), Step(144, 0.55f))

    /**
     * Compresses [source] to an MP4 no larger than [maxBytes]. Blocks the
     * calling (background) thread. [onProgress] gets 0–100.
     * Throws [TooLargeException] if it can't get under the limit.
     */
    fun compress(
        context: Context, source: Uri, maxBytes: Int, onProgress: (Int) -> Unit = {}
    ): File {
        check(Looper.myLooper() != Looper.getMainLooper()) { "compress() blocks; call it off the main thread" }

        val durationMs = durationMs(context, source)
        val (srcW, srcH) = displaySize(context, source)
        // 90% of the budget for the media itself; the rest is MP4/MMS
        // container overhead.
        val budgetBits = maxBytes * 8L * 9 / 10

        // Longest clip that fits at the minimum usable quality.
        val maxDurationMs = (budgetBits * 1000 / (MIN_VIDEO_BITRATE + AUDIO_BITRATE)).coerceAtLeast(3_000)
        var clipMs = if (durationMs > 0) minOf(durationMs, maxDurationMs) else maxDurationMs
        if (durationMs > clipMs) Log.i(TAG, "trimming ${durationMs}ms clip to ${clipMs}ms to fit ${maxBytes}B")

        val outDir = File(context.cacheDir, "video_out").apply { mkdirs() }
        var lastSize = 0L
        for ((i, step) in STEPS.withIndex()) {
            val totalBitrate = (budgetBits * 1000 / clipMs).toInt()
            val videoBitrate = ((totalBitrate - AUDIO_BITRATE) * step.bitrateScale).toInt()
                .coerceIn(MIN_VIDEO_BITRATE / 2, MAX_VIDEO_BITRATE)
            val out = File(outDir, "mms_video_${System.currentTimeMillis()}.mp4")
            Log.i(TAG, "attempt ${i + 1}: ${step.height}p, video ${videoBitrate}bps, clip ${clipMs}ms")

            runTransform(context, source, out, presentationFor(step.height, srcW, srcH), videoBitrate, clipMs) { p ->
                onProgress(((i * 100) + p) / STEPS.size.coerceAtLeast(1))
            }
            lastSize = out.length()
            if (lastSize in 1..maxBytes.toLong()) {
                onProgress(100)
                Log.i(TAG, "done: ${lastSize}B (limit ${maxBytes}B)")
                return out
            }
            out.delete()
            // Encoders often overshoot the requested bitrate at very low
            // rates — next step also shortens the clip a little.
            clipMs = (clipMs * 0.85).toLong().coerceAtLeast(3_000)
        }
        throw TooLargeException("Video is still ${lastSize / 1024} KB after compressing (limit ${maxBytes / 1024} KB)")
    }

    /** Scales the *short* side to [shortSide] px so portrait clips don't
     *  come out as a sliver (createForHeight alone would make a 1080×1920
     *  video 135×240). Dimensions are kept even, as encoders require. */
    private fun presentationFor(shortSide: Int, srcW: Int, srcH: Int): Presentation {
        if (srcW <= 0 || srcH <= 0 || srcW >= srcH) return Presentation.createForHeight(shortSide)
        val longSide = ((shortSide.toLong() * srcH / srcW).toInt() / 2) * 2
        return Presentation.createForWidthAndHeight(shortSide, longSide, Presentation.LAYOUT_SCALE_TO_FIT)
    }

    /** Width × height as displayed (rotation applied). */
    private fun displaySize(context: Context, uri: Uri): Pair<Int, Int> {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(context, uri)
            val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rot == 90 || rot == 270) h to w else w to h
        } catch (e: Exception) {
            0 to 0
        } finally {
            try { r.release() } catch (_: Exception) {}
        }
    }

    private fun durationMs(context: Context, uri: Uri): Long {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(context, uri)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (e: Exception) {
            0L
        } finally {
            try { r.release() } catch (_: Exception) {}
        }
    }

    /** Runs one Transformer export and waits for it. Transformer must be
     *  driven from a Looper thread, so it's started on the main thread
     *  while this (background) thread waits. */
    private fun runTransform(
        context: Context, source: Uri, out: File, presentation: Presentation, videoBitrate: Int, clipMs: Long,
        onProgress: (Int) -> Unit
    ) {
        val main = Handler(Looper.getMainLooper())
        val done = CountDownLatch(1)
        var error: Exception? = null
        var transformer: Transformer? = null

        val progressPoll = object : Runnable {
            val holder = ProgressHolder()
            override fun run() {
                val t = transformer ?: return
                if (t.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) onProgress(holder.progress)
                if (done.count > 0) main.postDelayed(this, 500)
            }
        }

        main.post {
            try {
                val encoderFactory = SmallAudioEncoderFactory(
                    DefaultEncoderFactory.Builder(context.applicationContext)
                        .setRequestedVideoEncoderSettings(
                            VideoEncoderSettings.Builder().setBitrate(videoBitrate).build()
                        )
                        .setEnableFallback(true)
                        .build()
                )
                val t = Transformer.Builder(context.applicationContext)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .setEncoderFactory(encoderFactory)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            done.countDown()
                        }

                        override fun onError(
                            composition: Composition, exportResult: ExportResult, exportException: ExportException
                        ) {
                            error = exportException
                            done.countDown()
                        }
                    })
                    .build()
                transformer = t

                val mediaItem = MediaItem.Builder()
                    .setUri(source)
                    .setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder().setEndPositionMs(clipMs).build()
                    )
                    .build()
                val toMono = ChannelMixingAudioProcessor().apply {
                    putChannelMixingMatrix(ChannelMixingMatrix.create(1, 1))
                    putChannelMixingMatrix(ChannelMixingMatrix.create(2, 1))
                }
                val edited = EditedMediaItem.Builder(mediaItem)
                    .setEffects(Effects(listOf(toMono), listOf(presentation)))
                    .build()
                t.start(edited, out.absolutePath)
                main.postDelayed(progressPoll, 500)
            } catch (e: Exception) {
                error = e
                done.countDown()
            }
        }

        if (!done.await(10, TimeUnit.MINUTES)) {
            main.post { transformer?.cancel() }
            throw Exception("Video compression timed out")
        }
        error?.let { throw it }
    }

    /**
     * Wraps the default encoder factory to (a) always re-encode both
     * tracks — otherwise Transformer may copy the original audio/video
     * through untouched — and (b) ask for a low audio bitrate; speech in
     * mono AAC is fine at 32 kbps and every byte saved goes to the picture.
     */
    private class SmallAudioEncoderFactory(private val delegate: Codec.EncoderFactory) : Codec.EncoderFactory {
        override fun createForAudioEncoding(format: Format): Codec =
            delegate.createForAudioEncoding(format.buildUpon().setAverageBitrate(AUDIO_BITRATE).build())

        override fun createForVideoEncoding(format: Format): Codec =
            delegate.createForVideoEncoding(format)

        override fun audioNeedsEncoding(): Boolean = true

        override fun videoNeedsEncoding(): Boolean = true
    }
}
