package com.turbotext.app

import android.content.Context
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.util.Log
import java.io.File

/**
 * Every notification sound TurboText can play, from three places:
 *  - the phone's own notification sounds (RingtoneManager), as
 *    content:// references;
 *  - TurboText's built-in pack, the Android Open Source Project's
 *    notification sounds (assets/sounds, Apache 2.0, see NOTICE.txt),
 *    as "asset:sounds/<file>";
 *  - the user's own files in Internal storage/notifications, as plain
 *    file paths (as the setting has always stored).
 * The chosen sound is saved as one of these references.
 */
object NotificationSounds {
    private const val TAG = "TurboTextSound"
    const val ASSET_PREFIX = "asset:"
    private const val ASSET_DIR = "sounds"

    enum class Source(val label: String) {
        PHONE("Phone sounds"), BUILT_IN("TurboText sounds"), MY_FILES("My sound files")
    }

    data class Sound(val name: String, val ref: String, val source: Source)

    /** The phone's own notification sounds, by their titles. */
    fun phone(context: Context): List<Sound> = try {
        val manager = RingtoneManager(context).apply { setType(RingtoneManager.TYPE_NOTIFICATION) }
        val cursor = manager.cursor
        val list = ArrayList<Sound>()
        while (cursor.moveToNext()) {
            val title = cursor.getString(RingtoneManager.TITLE_COLUMN_INDEX) ?: continue
            val uri = manager.getRingtoneUri(cursor.position) ?: continue
            list.add(Sound(title, uri.toString(), Source.PHONE))
        }
        list.distinctBy { it.ref }.sortedBy { it.name.lowercase() }
    } catch (e: Exception) {
        Log.w(TAG, "couldn't list the phone's sounds", e)
        emptyList()
    }

    /** TurboText's built-in sounds, by name. */
    fun builtIn(context: Context): List<Sound> = try {
        (context.assets.list(ASSET_DIR) ?: emptyArray())
            .filter { it.endsWith(".ogg", true) }
            .map { Sound(displayName(it), "$ASSET_PREFIX$ASSET_DIR/$it", Source.BUILT_IN) }
            .sortedBy { it.name.lowercase() }
    } catch (e: Exception) {
        Log.w(TAG, "couldn't list the built-in sounds", e)
        emptyList()
    }

    /** The user's own sound files (Internal storage/notifications). */
    fun myFiles(): List<Sound> = SoundNotificationHelper.listAvailableSounds()
        .map { Sound(it.nameWithoutExtension, it.absolutePath, Source.MY_FILES) }

    fun all(context: Context, source: Source): List<Sound> = when (source) {
        Source.PHONE -> phone(context)
        Source.BUILT_IN -> builtIn(context)
        Source.MY_FILES -> myFiles()
    }

    /** "F1_New_SMS.ogg" -> "F1 New SMS". */
    fun displayName(fileName: String): String =
        fileName.substringBeforeLast('.').replace('_', ' ').trim()

    /** The name to show for a saved [ref]. */
    fun nameOf(context: Context, ref: String): String = when {
        ref.startsWith(ASSET_PREFIX) -> displayName(ref.substringAfterLast('/'))
        ref.startsWith("content:") -> try {
            RingtoneManager.getRingtone(context, Uri.parse(ref))?.getTitle(context) ?: "Phone sound"
        } catch (e: Exception) {
            "Phone sound"
        }
        else -> File(ref).nameWithoutExtension
    }

    /** False when a saved sound is gone (a deleted file, a removed phone
     *  sound); the alert then plays no sound rather than fail silently. */
    fun exists(context: Context, ref: String): Boolean = when {
        ref.startsWith(ASSET_PREFIX) -> try {
            context.assets.openFd(ref.removePrefix(ASSET_PREFIX)).close()
            true
        } catch (e: Exception) {
            false
        }
        ref.startsWith("content:") -> try {
            context.contentResolver.openAssetFileDescriptor(Uri.parse(ref), "r")?.close() != null
        } catch (e: Exception) {
            false
        }
        else -> File(ref).exists()
    }

    /** Points [player] at [ref], whichever kind it is. */
    fun setDataSource(context: Context, player: MediaPlayer, ref: String) {
        when {
            ref.startsWith(ASSET_PREFIX) -> context.assets.openFd(ref.removePrefix(ASSET_PREFIX)).use { fd ->
                player.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
            }
            ref.startsWith("content:") -> player.setDataSource(context, Uri.parse(ref))
            else -> player.setDataSource(ref)
        }
    }
}
