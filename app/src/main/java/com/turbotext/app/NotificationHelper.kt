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

    /** rawAddress is needed (separately from displayName) to resolve which
     *  thread this belongs to, so tapping the notification can jump
     *  straight into that conversation instead of just the list. */
    fun showIncoming(
        context: Context,
        rawAddress: String,
        displayName: String,
        body: String,
        threadId: Long? = null,
        conversationAddress: String? = null
    ) {
        val manager = context.getSystemService(NotificationManager::class.java)

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

        val resolvedThreadId = threadId ?: try {
            android.provider.Telephony.Threads.getOrCreateThreadId(context, rawAddress)
        } catch (e: Exception) {
            -1L
        }

        val mainIntent = Intent(context, MainActivity::class.java)
        val conversationIntent = Intent(context, ConversationActivity::class.java).apply {
            putExtra("threadId", resolvedThreadId)
            putExtra("address", conversationAddress ?: rawAddress)
            putExtra("displayName", displayName)
        }
        val pendingIntent = TaskStackBuilder.create(context).run {
            // Building the back stack manually (conversation list, then the
            // thread on top) rather than relying on a manifest parent
            // declaration, so pressing Back from the thread still returns
            // to the conversation list instead of just closing the app.
            addNextIntent(mainIntent)
            addNextIntent(conversationIntent)
            getPendingIntent(0, android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
        }

        val notification = NotificationCompat.Builder(context, INCOMING_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.sym_action_chat)
            .setContentTitle(displayName)
            .setContentText(body)
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

        // Must match what cancelForAddress() below (and SoundNotificationHelper's
        // own notify/acknowledge pair) will be called with once the thread is
        // opened — for a group MMS thread that's the joined conversationAddress,
        // not the single rawAddress this notification happens to be about.
        // Keying on rawAddress alone left group-thread notifications (and the
        // outer-screen rich card, and the persistent alert) stuck forever,
        // since the id used to post never matched the id used to cancel.
        manager.notify((conversationAddress ?: rawAddress).hashCode(), notification)
    }

    /** Clears the incoming-message notification for a given address —
     *  called when the thread is actually opened, so it goes away even if
     *  you got there by opening the app directly rather than tapping the
     *  notification. */
    fun cancelForAddress(context: Context, address: String) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.cancel(address.hashCode())
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
            .setSmallIcon(android.R.drawable.sym_action_chat)
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
