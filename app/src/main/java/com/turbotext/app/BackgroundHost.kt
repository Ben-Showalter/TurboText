package com.turbotext.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * Keeps TurboText running in the background, as MatChat does: the
 * Background Helper (KeyButtonAccessibilityService) when it's turned on,
 * with no notification; otherwise [KeepAliveService], a foreground service,
 * which Android only allows with a persistent "running" notification.
 */
object BackgroundHost {
    private const val TAG = "TurboTextBackground"

    /** Starts or stops [KeepAliveService] to match the helper. Pass
     *  [helperRunning] when the helper itself is the caller (its own
     *  enabled flag may not have caught up yet). */
    fun ensureRunning(context: Context, helperRunning: Boolean = AccessibilityHelper.isServiceEnabled(context)) {
        val app = context.applicationContext
        val intent = Intent(app, KeepAliveService::class.java)
        try {
            if (helperRunning) {
                app.stopService(intent)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                app.startForegroundService(intent)
            } else {
                app.startService(intent)
            }
        } catch (e: Exception) {
            // Starting a foreground service from the background can be
            // refused; the next app open or restart tries again.
            Log.w(TAG, "couldn't switch background host", e)
        }
    }
}

/** The fallback keep-alive, while the Background Helper is off. Its only
 *  job is to be a foreground service; the notification says how to make
 *  it go away. */
class KeepAliveService : Service() {

    companion object {
        private const val CHANNEL_ID = "background_running"
        private const val NOTIFICATION_ID = 7301
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        return START_STICKY
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Running in background", NotificationManager.IMPORTANCE_MIN).apply {
                setSound(null, null)
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, AdvancedSettingsActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_minimal)
            .setContentTitle("TurboText is running")
            .setContentText("Turn on Background Helper in Settings > Advanced to hide this.")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setShowWhen(false)
            .setContentIntent(open)
            .build()
    }
}
