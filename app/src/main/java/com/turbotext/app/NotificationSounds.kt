package com.turbotext.app

import android.content.Context
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.util.Log
import java.io.File

/**
 * Every notification sound TurboText can play, from three places:
 *  - the phone's own notification sounds (the media store), as
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

    /**
     * The phone's own notification sounds, by their titles. Read from the
     * media store rather than RingtoneManager: on this phone its
     * "notification" list also had every ringtone in it. A sound counts
     * when it's filed under a notifications folder, or flagged as a
     * notification and not as a ringtone or alarm; anything filed under a
     * ringtones or alarms folder never does.
     */
    fun phone(context: Context): List<Sound> {
        val list = ArrayList<Sound>()
        for (collection in listOf(
            android.provider.MediaStore.Audio.Media.INTERNAL_CONTENT_URI,
            android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        )) {
            try {
                context.contentResolver.query(
                    collection,
                    arrayOf("_id", "title", "_data", "is_notification", "is_ringtone", "is_alarm"),
                    null, null, "title"
                )?.use { c ->
                    while (c.moveToNext()) {
                        val path = c.getString(2).orEmpty()
                        val keep = isNotificationSound(path, c.getInt(3) != 0, c.getInt(4) != 0, c.getInt(5) != 0)
                        if (!keep) continue
                        val title = c.getString(1)?.takeIf { it.isNotBlank() } ?: File(path).nameWithoutExtension
                        list.add(Sound(title, android.content.ContentUris.withAppendedId(collection, c.getLong(0)).toString(), Source.PHONE))
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "couldn't list the phone's sounds in $collection", e)
            }
        }
        return list.distinctBy { it.ref }.sortedBy { it.name.lowercase() }
    }

    /** See [phone]: whether one media-store sound is a notification sound. */
    fun isNotificationSound(path: String, isNotification: Boolean, isRingtone: Boolean, isAlarm: Boolean): Boolean {
        val p = path.lowercase()
        if ("/ringtones/" in p || "/alarms/" in p) return false
        if ("/notifications/" in p) return true
        return isNotification && !isRingtone && !isAlarm
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
