package com.turbotext.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.util.concurrent.Executors

/**
 * Picture and video thumbnails for message bubbles.
 *
 * Decodes at roughly the size it's shown (inSampleSize) instead of full
 * resolution — a camera photo decoded whole is 10+ MB, and doing that on
 * every bind was causing long garbage-collection pauses mid-scroll. One
 * shared background thread does all decoding (rather than a new thread
 * per bind), and the cache is capped by bytes, not entry count.
 */
object ThumbnailLoader {

    private val cache = object : LruCache<String, Bitmap>(4 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    /** Shows a thumbnail of [uri] in [view], at most [sizePx] on its
     *  longer side. [isVideo] grabs a frame instead of decoding an image. */
    fun load(view: ImageView, uri: String, sizePx: Int, isVideo: Boolean = false) {
        val key = "$uri|$sizePx"
        view.tag = key
        cache.get(key)?.let { view.setImageBitmap(it); return }
        view.setImageDrawable(null)
        val ctx = view.context.applicationContext
        executor.execute {
            val bmp = try {
                if (isVideo) videoFrame(ctx, Uri.parse(uri), sizePx) else decodeSampled(ctx, Uri.parse(uri), sizePx)
            } catch (e: Exception) {
                android.util.Log.w("TurboTextPerf", "thumbnail decode failed", e)
                null
            } ?: return@execute
            cache.put(key, bmp)
            main.post { if (view.tag == key) view.setImageBitmap(bmp) }
        }
    }

    fun decodeSampled(context: Context, uri: Uri, maxSide: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        return context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    }

    private fun videoFrame(context: Context, uri: Uri, maxSide: Int): Bitmap? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(context, uri)
            val frame = r.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return null
            val scale = maxSide.toFloat() / maxOf(frame.width, frame.height)
            if (scale >= 1f) frame
            else Bitmap.createScaledBitmap(frame, (frame.width * scale).toInt(), (frame.height * scale).toInt(), true)
                .also { if (it !== frame) frame.recycle() }
        } finally {
            try { r.release() } catch (_: Exception) {}
        }
    }
}
