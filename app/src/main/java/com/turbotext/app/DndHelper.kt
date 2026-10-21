package com.turbotext.app

import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.util.Log

/**
 * Whether a new-message alert may make noise, given the phone's Do Not
 * Disturb and ringer settings. TurboText plays its alert sound and
 * vibration itself (the notification channel is silent so the custom
 * sounds and repeats work), so the system's own DND handling never got a
 * say: every alert played through DND. The notification itself still
 * posts, and the cover screen still shows the message — only sound and
 * vibration are held back.
 */
object DndHelper {

    enum class Level { FULL, VIBRATE_ONLY, SILENT }

    // NotificationManager / AudioManager values, repeated here so
    // [decide] can be unit-tested without the framework.
    private const val FILTER_PRIORITY = 2
    private const val FILTER_NONE = 3
    private const val FILTER_ALARMS = 4
    private const val RINGER_SILENT = 0
    private const val RINGER_VIBRATE = 1
    private const val SENDERS_ANY = 0
    private const val SENDERS_CONTACTS = 1
    private const val SENDERS_STARRED = 2
    private const val CATEGORY_MESSAGES = 1 shl 2

    /** [sender] is the number the message came from, used when DND lets
     *  only contacts or starred contacts through; null when unknown. */
    fun level(context: Context, sender: String?): Level {
        return try {
            val nm = context.getSystemService(NotificationManager::class.java)
            val filter = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) nm.currentInterruptionFilter else 1
            val ringer = (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager).ringerMode
            val letsThrough = filter == FILTER_PRIORITY && priorityLetsThrough(context, nm, sender)
            decide(filter, ringer, letsThrough)
        } catch (e: Exception) {
            Log.w("TurboTextDnd", "couldn't read Do Not Disturb state", e)
            Level.FULL
        }
    }

    private fun priorityLetsThrough(context: Context, nm: NotificationManager, sender: String?): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false
        val policy = nm.notificationPolicy ?: return false
        if (policy.priorityCategories and CATEGORY_MESSAGES == 0) return false
        val (isContact, isStarred) = if (sender != null) ContactHelper.lookupFlags(context, sender) else false to false
        return senderAllowed(policy.priorityMessageSenders, isContact, isStarred)
    }

    /** [priorityLetsThrough]: DND is on "priority only" and its message
     *  rule allows this sender. */
    fun decide(filter: Int, ringerMode: Int, priorityLetsThrough: Boolean): Level {
        val dndBlocks = when (filter) {
            FILTER_NONE, FILTER_ALARMS -> true
            FILTER_PRIORITY -> !priorityLetsThrough
            else -> false
        }
        if (dndBlocks) return Level.SILENT
        return when (ringerMode) {
            RINGER_SILENT -> Level.SILENT
            RINGER_VIBRATE -> Level.VIBRATE_ONLY
            else -> Level.FULL
        }
    }

    fun senderAllowed(senders: Int, isContact: Boolean, isStarred: Boolean): Boolean = when (senders) {
        SENDERS_ANY -> true
        SENDERS_CONTACTS -> isContact || isStarred
        SENDERS_STARRED -> isStarred
        else -> false
    }
}
