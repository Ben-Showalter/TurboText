package com.turbotext.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.TaskStackBuilder

object NotificationHelper {
    private const val INCOMING_CHANNEL_ID = "incoming_sms_v3"
    // (SHORTCUT_CHANNEL_ID/NOTIFICATION_ID removed — see note near the
    // bottom of this file.)

    /** Fixed id for the "couldn't be downloaded" notice — it isn't tied to
     *  any conversation, so it gets one slot that a later success clears. */
    private const val MMS_DOWNLOAD_FAILED_ID = 9998

    /** The one key every per-conversation alert uses — the notification,
     *  its tap action, the outer-screen card and the repeat alarm — so
     *  posting and clearing always agree. It's the thread id: an SMS and
     *  an MMS from the same person, a group thread, or an address the
     *  provider formats differently ("+1555…" vs "555…") all land on the
     *  same conversation. Falls back to the address only if the thread
     *  couldn't be resolved. */
    fun conversationKey(threadId: Long, address: String): String =
        if (threadId > 0) "thread:$threadId" else address

    private fun ensureChannel(manager: NotificationManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                INCOMING_CHANNEL_ID, "Messages", NotificationManager.IMPORTANCE_HIGH
            )
            // Channel-level lock screen visibility — separate from the
            // per-notification setVisibility() call below, and the
            // piece that was actually missing for this to show on the
            // lock screen at all.
            channel.lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
            channel.enableLights(true)
            channel.lightColor = android.graphics.Color.BLUE
            // Sound is played directly via SoundNotificationHelper now
            // (so the custom file / no-sound / repeat options actually
            // work) — silencing the channel's own sound avoids playing
            // both at once.
            channel.setSound(null, null)
            manager.createNotificationChannel(channel)
        }
    }

    /** rawAddress is needed (separately from displayName) to resolve which
     *  thread this belongs to, so tapping the notification can jump
     *  straight into that conversation instead of just the list.
     *  Returns the thread id it resolved (-1 if it couldn't), for the
     *  caller to key its sound/outer-screen alert with the same
     *  [conversationKey]. */
    fun showIncoming(
        context: Context,
        rawAddress: String,
        displayName: String,
        body: String,
        threadId: Long? = null,
        conversationAddress: String? = null
    ): Long {
        val manager = context.getSystemService(NotificationManager::class.java)
        ensureChannel(manager)

        val resolvedThreadId = threadId ?: try {
            android.provider.Telephony.Threads.getOrCreateThreadId(context, rawAddress)
        } catch (e: Exception) {
            -1L
        }

        val address = conversationAddress ?: rawAddress
        val key = conversationKey(resolvedThreadId, address)
        // The tap action stays per conversation; the notification's own id
        // changes with every message (below).
        val requestCode = key.hashCode()

        val mainIntent = Intent(context, MainActivity::class.java)
        val conversationIntent = Intent(context, ConversationActivity::class.java).apply {
            putExtra("threadId", resolvedThreadId)
            putExtra("address", address)
            putExtra("displayName", displayName)
        }
        val pendingIntent = TaskStackBuilder.create(context).run {
            // Building the back stack manually (conversation list, then the
            // thread on top) rather than relying on a manifest parent
            // declaration, so pressing Back from the thread still returns
            // to the conversation list instead of just closing the app.
            addNextIntent(mainIntent)
            addNextIntent(conversationIntent)
            // One request code per conversation. With a shared code every
            // notification's tap action was the same PendingIntent, updated
            // to whichever conversation notified last — so tapping an older
            // notification opened the wrong thread.
            getPendingIntent(requestCode, android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
        }

        val slot = nextSlot(context, key)
        val notification = NotificationCompat.Builder(context, INCOMING_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_turbotext)
            .setContentTitle(displayName)
            .setContentText(body)
            .setSubText(otherMessages(slot.others))
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            // The channel's own enableLights(true) is what actually turns
            // the LED on — this doesn't add that, it's a low-risk attempt
            // at getting a solid rather than blinking light. The public
            // API for blink timing was deprecated once channels took
            // over on API 26+, and channels themselves don't expose
            // blink-rate control at all — but this device's driver stack
            // is heavily customized, so it's worth trying whether it
            // still honors the old per-notification override. Harmless
            // either way if it doesn't.
            .setLights(android.graphics.Color.BLUE, 1, 0)
            .build()

        // Cancel, then post under a new id: a fresh notification alerts,
        // where an update of the same id may stay silent. Only the newest
        // one is ever in the shade.
        slot.old?.let { manager.cancel(it) }
        manager.cancel(requestCode) // posted by builds before this one
        manager.notify(slot.id, notification)
        val importance = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            manager.getNotificationChannel(INCOMING_CHANNEL_ID)?.importance?.toString() ?: "?" else "n/a"
        AppLog.event(
            "Notify",
            "$key: cancelled ${slot.old ?: "none"}, posted ${slot.id}, importance $importance, " +
                "text ${body.length} chars, +${slot.others} others"
        )
        return resolvedThreadId
    }

    private const val PREFS = "message_notifications"
    private const val FIRST_ID = 100_000
    private val slotLock = Any()

    /** The notification showing for a conversation (if any), the new id
     *  to post this message under, and how many messages came before it
     *  since the conversation was last cleared. */
    private data class Slot(val old: Int?, val id: Int, val others: Int)

    private fun nextSlot(context: Context, key: String): Slot = synchronized(slotLock) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val old = if (prefs.contains("id:$key")) prefs.getInt("id:$key", 0) else null
        val others = prefs.getInt("count:$key", 0)
        var id = prefs.getInt("next_id", FIRST_ID)
        if (id < FIRST_ID || id >= Int.MAX_VALUE - 1) id = FIRST_ID
        prefs.edit()
            .putInt("next_id", id + 1)
            .putInt("id:$key", id)
            .putInt("count:$key", others + 1)
            .commit()
        Slot(old, id, others)
    }

    /** The shade's small line for the messages before the newest one. */
    internal fun otherMessages(others: Int): String? = when {
        others <= 0 -> null
        others == 1 -> "+1 other message"
        else -> "+$others other messages"
    }

    /** Shown when an MMS failed to download and nothing was saved, so
     *  there's no conversation to point at — tapping it opens the
     *  conversation list, where the "press OK to download" notice is.
     *  Cleared by [cancelMmsDownloadFailed] once an MMS comes through. */
    fun showMmsDownloadFailed(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        ensureChannel(manager)
        val pendingIntent = android.app.PendingIntent.getActivity(
            context, MMS_DOWNLOAD_FAILED_ID, Intent(context, MainActivity::class.java),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, INCOMING_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_turbotext)
            .setContentTitle("Multimedia message")
            .setContentText("A multimedia message couldn't be downloaded")
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
        manager.notify(MMS_DOWNLOAD_FAILED_ID, notification)
    }

    fun cancelMmsDownloadFailed(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(MMS_DOWNLOAD_FAILED_ID)
    }

    /** Clears the incoming-message notification for a conversation —
     *  called when the thread is actually opened, so it goes away even if
     *  you got there by opening the app directly rather than tapping the
     *  notification. [key] is [conversationKey]. */
    fun cancelForConversation(context: Context, key: String) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.cancel(key.hashCode()) // posted by builds before this one
        synchronized(slotLock) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (prefs.contains("id:$key")) manager.cancel(prefs.getInt("id:$key", 0))
            if (prefs.contains("id:$key") || prefs.contains("count:$key"))
                prefs.edit().remove("id:$key").remove("count:$key").apply()
        }
    }

    fun showProvisioningConfirmation(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(INCOMING_CHANNEL_ID, "Messages", NotificationManager.IMPORTANCE_HIGH).apply {
                    lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
                    enableLights(true)
                    lightColor = android.graphics.Color.BLUE
                }
            )
        }
        val notification = NotificationCompat.Builder(context, INCOMING_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_turbotext)
            .setContentTitle("TurboText")
            .setContentText("The advanced features of TurboText have been activated successfully")
            .setAutoCancel(true)
            .setTimeoutAfter(5000)
            .build()
        manager.notify(9999, notification)
    }

    /** A permanent shortcut sitting in the notification shade to reopen
     *  TurboText — on a MIN-importance channel, which Android specifically
     *  keeps out of the status bar (no icon at the top of the screen) and
     *  silent, while still always being present when the shade is pulled
     *  down. Incoming messages use a separate, higher-importance channel
     *  above, which DOES show a status bar icon — so a status bar icon
     *  only appears when there's an actual new message.
     *
     *  No longer used — no foreground service/persistent notification
     *  runs anymore. KeyButtonAccessibilityService's PendingIntent-based
     *  launch (see its launchTurboText()) turned out to be what actually
     *  fixed the right-soft-key relaunch delay, not a foreground
     *  service's background-activity-start exemption — kept only as a
     *  historical note, not called anymore. */
}
