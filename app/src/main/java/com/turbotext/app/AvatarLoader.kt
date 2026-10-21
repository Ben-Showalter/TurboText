package com.turbotext.app

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.util.LruCache
import android.widget.ImageView
import androidx.core.content.ContextCompat
import java.util.Collections
import java.util.concurrent.Executors

/**
 * Round contact avatars for the conversation list and group threads.
 *
 * A contact's own photo is used when there is one; otherwise a colored
 * circle with their initials, the color picked from the phone number so
 * the same person always gets the same color. Both kinds are rendered
 * once into small bitmaps and cached — binding a row never allocates or
 * decodes on the main thread, which matters for smooth scrolling on this
 * phone.
 */
object AvatarLoader {

    /** Fallback palette — eight colors that stay readable with white
     *  initials on both the dark and light themes. */
    private val COLORS = intArrayOf(
        0xFF368BD6.toInt(), 0xFFAC3BA8.toInt(), 0xFF03B381.toInt(), 0xFFE64F7A.toInt(),
        0xFFFF812D.toInt(), 0xFF2DC2C5.toInt(), 0xFF5C56F5.toInt(), 0xFF74D12C.toInt()
    )

    /** Keyed by "address|sizePx". Sized in bytes — avatars are tiny
     *  (40dp ≈ 40–60px), so 1.5 MB holds a few hundred. */
    private val photos = object : LruCache<String, Bitmap>(1536 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val initials = object : LruCache<String, Bitmap>(512 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    /** Addresses already checked that have no contact photo — so we
     *  don't hit the contacts provider again on every rebind. */
    private val noPhoto: MutableSet<String> = Collections.synchronizedSet(HashSet())

    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** Shows [address]'s avatar in [view]. [displayName] supplies the
     *  initials. For a group, pass the comma-separated member names —
     *  the first two members' initials are shown. */
    fun bind(view: ImageView, address: String, displayName: String) {
        val size = avatarSize(view)
        val key = "$address|$size"
        view.tag = key

        photos.get(key)?.let { view.setImageBitmap(it); return }
        view.setImageBitmap(initialsBitmap(address, displayName, size))

        if (isGroup(address) || noPhoto.contains(address)) return
        val ctx = view.context.applicationContext
        if (ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) return

        executor.execute {
            val bmp = loadPhoto(ctx, address, size)
            if (bmp == null) {
                noPhoto.add(address)
                return@execute
            }
            photos.put(key, bmp)
            main.post {
                // The row may have been recycled for someone else while
                // the photo was loading.
                if (view.tag == key) view.setImageBitmap(bmp)
            }
        }
    }

    /** Contacts changed (a photo or name may be new) — forget everything
     *  so the next bind looks again. */
    fun invalidate() {
        photos.evictAll()
        initials.evictAll()
        noPhoto.clear()
    }

    fun colorFor(address: String): Int =
        COLORS[(address.hashCode() and Int.MAX_VALUE) % COLORS.size]

    private fun isGroup(address: String) = address.contains(',') || address.contains(';')

    private fun avatarSize(view: ImageView): Int {
        val lp = view.layoutParams
        val fromLayout = lp?.width?.takeIf { it > 0 } ?: 0
        return if (fromLayout > 0) fromLayout else (40 * view.resources.displayMetrics.density).toInt()
    }

    private fun initialsFor(displayName: String): String {
        val parts = if (displayName.contains(',')) {
            // Group: one letter from each of the first two members.
            displayName.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                .mapNotNull { it.firstOrNull { c -> c.isLetterOrDigit() } }
                .take(2).joinToString("")
        } else {
            val words = displayName.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            val letters = words.mapNotNull { w -> w.firstOrNull { it.isLetter() } }
            when {
                letters.isEmpty() -> "#"
                letters.size == 1 -> letters[0].toString()
                else -> "${letters.first()}${letters.last()}"
            }
        }
        return parts.uppercase().ifEmpty { "#" }
    }

    private fun initialsBitmap(address: String, displayName: String, size: Int): Bitmap {
        val text = initialsFor(displayName)
        val color = colorFor(address)
        val key = "$text|$color|$size"
        initials.get(key)?.let { return it }

        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        fillPaint.shader = null
        fillPaint.color = color
        val r = size / 2f
        canvas.drawCircle(r, r, r, fillPaint)
        textPaint.textSize = size * (if (text.length > 1) 0.40f else 0.48f)
        val y = r - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(text, r, y, textPaint)
        initials.put(key, bmp)
        return bmp
    }

    private fun loadPhoto(context: Context, address: String, size: Int): Bitmap? {
        return try {
            val lookup = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(address))
            val thumb = context.contentResolver.query(
                lookup, arrayOf(ContactsContract.PhoneLookup.PHOTO_THUMBNAIL_URI), null, null, null
            )?.use { if (it.moveToFirst()) it.getString(0) else null } ?: return null
            val src = context.contentResolver.openInputStream(Uri.parse(thumb))?.use {
                BitmapFactory.decodeStream(it)
            } ?: return null
            circleCrop(src, size).also { if (it !== src) src.recycle() }
        } catch (e: Exception) {
            android.util.Log.w("TurboTextAvatar", "photo load failed for $address", e)
            null
        }
    }

    private fun circleCrop(src: Bitmap, size: Int): Bitmap {
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val side = minOf(src.width, src.height)
        val scale = size.toFloat() / side
        val shader = BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        val m = android.graphics.Matrix()
        m.setScale(scale, scale)
        m.postTranslate(-(src.width - side) / 2f * scale, -(src.height - side) / 2f * scale)
        shader.setLocalMatrix(m)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.shader = shader }
        val r = size / 2f
        canvas.drawCircle(r, r, r, paint)
        return out
    }
}
