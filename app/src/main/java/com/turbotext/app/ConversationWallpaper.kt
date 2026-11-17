package com.turbotext.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.util.LruCache
import java.io.File
import java.io.InputStream
import kotlin.random.Random

/**
 * A conversation's background: a picture from TurboText's pack
 * (assets/wallpapers), picked at random the first time and kept for that
 * conversation, unless one was chosen in Contact Options > Conversation
 * Background — a pack picture, a picture from the phone's gallery (a
 * cropped copy in files/wallpapers), or none. Groups get one too.
 * Off for every conversation in Settings > Theme > Conversation Background.
 */
object ConversationWallpaper {
    private const val TAG = "TurboTextWallpaper"
    private const val DIR = "wallpapers"
    private const val PREFS = "conversation_wallpaper"

    /** What's chosen for a conversation. */
    sealed class Choice {
        /** The random pack picture kept for the conversation (the default). */
        object Random : Choice()
        object None : Choice()
        /** A gallery picture, saved as [customFile]. */
        object Custom : Choice()
        data class Pack(val file: String) : Choice()
    }

    /** The saved value: "random", "none", "custom" or "pack:<file>". */
    internal fun parseChoice(stored: String?): Choice = when {
        stored == null || stored == "random" -> Choice.Random
        stored == "none" -> Choice.None
        stored == "custom" -> Choice.Custom
        stored.startsWith("pack:") -> Choice.Pack(stored.removePrefix("pack:"))
        else -> Choice.Random
    }

    internal fun encode(choice: Choice): String = when (choice) {
        Choice.Random -> "random"
        Choice.None -> "none"
        Choice.Custom -> "custom"
        is Choice.Pack -> "pack:${choice.file}"
    }

    /** How Contact Options shows it: "Random", "None", "Gallery Picture", "Picture 12". */
    internal fun label(choice: Choice): String = when (choice) {
        Choice.Random -> "Random"
        Choice.None -> "None"
        Choice.Custom -> "Gallery Picture"
        is Choice.Pack -> "Picture ${packNumber(choice.file) ?: choice.file}"
    }

    /** 12 for "wallpaper_pack_12.jpg". */
    internal fun packNumber(file: String): Int? =
        Regex("(\\d+)\\.[a-zA-Z]+$").find(file)?.groupValues?.get(1)?.toIntOrNull()

    private val cache = object : LruCache<String, Bitmap>(3 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    /** The pack's files, in name order. */
    fun files(context: Context): List<String> = try {
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

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The random pack picture kept for [threadId], picking (and saving)
     *  one the first time. */
    fun choiceFor(context: Context, threadId: Long): String? {
        val prefs = prefs(context)
        val stored = prefs.getString("wallpaper:$threadId", null)
        val chosen = pick(stored, files(context)) ?: return null
        if (chosen != stored) prefs.edit().putString("wallpaper:$threadId", chosen).apply()
        return chosen
    }

    /** What's chosen for [threadId] (Random unless set). */
    fun getChoice(context: Context, threadId: Long): Choice =
        parseChoice(prefs(context).getString("choice:$threadId", null))

    fun setChoice(context: Context, threadId: Long, choice: Choice) {
        prefs(context).edit().putString("choice:$threadId", encode(choice)).apply()
        if (choice != Choice.Custom) customFile(context, threadId).delete()
        clearCache(threadId)
        AppLog.event("Background", "thread $threadId: ${encode(choice)}")
    }

    /** Where a gallery picture chosen for [threadId] is kept. */
    fun customFile(context: Context, threadId: Long): File =
        File(File(context.filesDir, DIR).apply { mkdirs() }, "custom_$threadId.jpg")

    fun clearCache(threadId: Long) {
        cache.snapshot().keys.filter { it.startsWith("$threadId|") }.forEach { cache.remove(it) }
    }

    /**
     * The picture for this conversation, cropped to [width]×[height], or
     * null when the setting is off, None is chosen, or nothing could be
     * loaded. Off the main thread.
     */
    @Suppress("UNUSED_PARAMETER")
    fun load(context: Context, threadId: Long, address: String, width: Int, height: Int): Bitmap? {
        if (!SettingsHelper.isConversationWallpaper(context) || width <= 0 || height <= 0) return null
        var choice = getChoice(context, threadId)
        if (choice == Choice.None) {
            Log.i(TAG, "none: chosen for $threadId")
            return null
        }
        val custom = customFile(context, threadId)
        if (choice == Choice.Custom && !custom.exists()) choice = Choice.Random
        if (choice is Choice.Pack && choice.file !in files(context)) choice = Choice.Random
        val source = when (choice) {
            Choice.Custom -> "custom"
            is Choice.Pack -> choice.file
            else -> choiceFor(context, threadId) ?: return null
        }
        val key = "$threadId|$source|${custom.takeIf { choice == Choice.Custom }?.lastModified() ?: 0}|${width}x$height"
        cache.get(key)?.let { return it }
        val bitmap = try {
            if (choice == Choice.Custom) decodeCropped({ custom.inputStream() }, width, height)
            else decodeCropped({ context.assets.open("$DIR/$source") }, width, height)
        } catch (e: Exception) {
            Log.w(TAG, "couldn't load the background for $threadId", e)
            null
        } ?: run {
            Log.i(TAG, "none: nothing loaded for $threadId")
            return null
        }
        Log.i(TAG, "background for $threadId: $source at ${width}x$height")
        cache.put(key, bitmap)
        return bitmap
    }

    /** A pack picture as a small thumbnail (the Conversation Background grid). */
    fun packThumbnail(context: Context, file: String, width: Int, height: Int): Bitmap? = try {
        decodeCropped({ context.assets.open("$DIR/$file") }, width, height)
    } catch (e: Exception) {
        null
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
