package com.turbotext.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.provider.ContactsContract
import android.util.Log
import android.util.LruCache
import java.io.InputStream
import kotlin.random.Random

/**
 * The picture behind a conversation's messages: the contact's own photo
 * when they have one (a one-person conversation), otherwise a wallpaper
 * from TurboText's pack (assets/wallpapers), picked at random the first
 * time and kept for that conversation. Groups get a wallpaper too.
 * Off in Settings > Theme > Conversation Wallpaper.
 */
object ConversationWallpaper {
    private const val TAG = "TurboTextWallpaper"
    private const val DIR = "wallpapers"
    private const val PREFS = "conversation_wallpaper"

    private val cache = object : LruCache<String, Bitmap>(3 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    /** The pack's files, in name order. */
    private fun files(context: Context): List<String> = try {
        (context.assets.list(DIR) ?: emptyArray()).filter { it.endsWith(".jpg", true) || it.endsWith(".png", true) }.sorted()
    } catch (e: Exception) {
        emptyList()
    }

    /** [stored] if it's still in [files], otherwise a random one of them
     *  (null when there are none). */
    fun pick(stored: String?, files: List<String>, random: Random = Random.Default): String? = when {
        files.isEmpty() -> null
        stored != null && stored in files -> stored
        else -> files[random.nextInt(files.size)]
    }

    /** The wallpaper kept for [threadId], picking (and saving) one the
     *  first time. */
    fun choiceFor(context: Context, threadId: Long): String? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = prefs.getString("wallpaper:$threadId", null)
        val chosen = pick(stored, files(context)) ?: return null
        if (chosen != stored) prefs.edit().putString("wallpaper:$threadId", chosen).apply()
        return chosen
    }

    /**
     * The picture for this conversation, cropped to [width]×[height], or
     * null when the setting is off or nothing could be loaded. Off the
     * main thread.
     */
    fun load(context: Context, threadId: Long, address: String, width: Int, height: Int): Bitmap? {
        if (!SettingsHelper.isConversationWallpaper(context) || width <= 0 || height <= 0) return null
        val isGroup = address.contains(',') || address.contains(';')
        val contact = if (isGroup) null else AvatarLoader.contactUriFor(context, address)
        val key = "${contact ?: choiceFor(context, threadId)}|${width}x$height"
        cache.get(key)?.let { return it }
        val bitmap = try {
            val fromContact = contact?.let { uri ->
                ContactsContract.Contacts.openContactPhotoInputStream(context.contentResolver, uri, true)
                    ?.let { stream -> decodeCropped({ stream }, width, height) }
            }
            fromContact ?: choiceFor(context, threadId)?.let { file ->
                decodeCropped({ context.assets.open("$DIR/$file") }, width, height)
            }
        } catch (e: Exception) {
            Log.w(TAG, "couldn't load the wallpaper for $threadId", e)
            null
        } ?: return null
        cache.put(key, bitmap)
        return bitmap
    }

    /** Decodes a picture no bigger than needed, then crops it to fill
     *  [width]×[height] from the centre (like centerCrop). */
    private fun decodeCropped(open: () -> InputStream, width: Int, height: Int): Bitmap? {
        val bytes = open().use { it.readBytes() }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= width && bounds.outHeight / (sample * 2) >= height) sample *= 2
        val src = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        val scale = maxOf(width.toFloat() / src.width, height.toFloat() / src.height)
        val cropW = (width / scale).toInt().coerceIn(1, src.width)
        val cropH = (height / scale).toInt().coerceIn(1, src.height)
        val x = (src.width - cropW) / 2
        val y = (src.height - cropH) / 2
        val cropped = Bitmap.createBitmap(src, x, y, cropW, cropH)
        val out = Bitmap.createScaledBitmap(cropped, width, height, true)
        if (cropped !== out && cropped !== src) cropped.recycle()
        if (src !== out) src.recycle()
        return out
    }
}
