package com.turbotext.app

import android.content.Context

/**
 * "Move to trash" doesn't touch the real SMS/MMS provider data — it just
 * remembers which messages are trashed (locally, in this app, with a
 * timestamp) and filters them out of the normal thread view. After 14
 * days, purgeExpired() actually deletes the underlying message for real
 * — see SmsRepository.purgeExpiredTrash(), which is what calls the
 * delete callback below.
 */
object TrashHelper {
    private const val PREFS = "message_pro_trash"
    const val EXPIRY_MS = 14L * 24 * 60 * 60 * 1000

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** SMS and MMS ids come from different tables and can collide
     *  numerically, so the type is baked into the key. */
    fun key(message: Message): String = "${if (message.isMms) "mms" else "sms"}:${message.id}"

    fun trash(context: Context, message: Message) {
        prefs(context).edit().putLong(key(message), System.currentTimeMillis()).apply()
    }

    fun isTrashed(context: Context, message: Message): Boolean =
        prefs(context).contains(key(message))

    fun remove(context: Context, key: String) {
        prefs(context).edit().remove(key).apply()
    }

    /** key -> timestamp it was trashed at. */
    fun allTrashedEntries(context: Context): Map<String, Long> {
        val all = prefs(context).all
        val result = mutableMapOf<String, Long>()
        for ((k, v) in all) {
            if (v is Long) result[k] = v
        }
        return result
    }

    fun allTrashedKeys(context: Context): Set<String> = allTrashedEntries(context).keys

    /** Finds every trash entry older than 14 days and, for each, calls
     *  [deleteReal] (the caller does the actual provider delete) before
     *  removing it from the trash tracking. */
    fun purgeExpired(context: Context, deleteReal: (isMms: Boolean, id: Long) -> Unit) {
        val cutoff = System.currentTimeMillis() - EXPIRY_MS
        for ((key, timestamp) in allTrashedEntries(context)) {
            if (timestamp < cutoff) {
                val parts = key.split(":")
                if (parts.size == 2) {
                    val isMms = parts[0] == "mms"
                    val id = parts[1].toLongOrNull()
                    if (id != null) {
                        try {
                            deleteReal(isMms, id)
                        } catch (e: Exception) {
                            // Leave it in trash tracking if the real delete
                            // failed — we'll retry next time rather than
                            // silently losing track of it.
                            continue
                        }
                    }
                }
                remove(context, key)
            }
        }
    }
}
