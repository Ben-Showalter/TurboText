package com.turbotext.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Telephony
import android.util.Log
import androidx.core.app.NotificationCompat
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Sends a broadcast list's message to each member as their own text, one
 * at a time: each waits for the carrier's answer (or [WAIT_MS]) and then
 * [GAP_MS] more, so a list of a couple of hundred doesn't go out as a
 * burst carriers flag as spam. Progress shows in a notification; the
 * send carries on with the screen closed. One broadcast at a time.
 */
object BroadcastSender {
    private const val TAG = "TurboTextBroadcast"
    private const val CHANNEL_ID = "broadcast_progress"
    private const val NOTIFICATION_ID = 4710

    /** Pause between two texts after the first one's result is in. */
    private const val GAP_MS = 3_000L

    /** Longest wait for the carrier to answer before moving on. */
    private const val WAIT_MS = 30_000L

    private val executor = Executors.newSingleThreadExecutor()
    private val running = AtomicBoolean(false)

    val isRunning: Boolean get() = running.get()

    /** Starts sending; false when another broadcast is still going. */
    fun start(context: Context, list: BroadcastList, body: String): Boolean {
        if (!running.compareAndSet(false, true)) return false
        val app = context.applicationContext
        executor.execute {
            var sent = 0
            var failed = 0
            try {
                val repo = SmsRepository(app)
                list.contacts.forEachIndexed { i, contact ->
                    notify(app, list, "Sending ${i + 1} of ${list.contacts.size}…", ongoing = true, progress = i)
                    val ok = try {
                        val row = repo.sendMessage(contact.number, body)
                        row != null && awaitResult(app, row)
                    } catch (e: Exception) {
                        Log.w(TAG, "failed to send to one member", e)
                        false
                    }
                    if (ok) sent++ else failed++
                    if (i < list.contacts.size - 1) Thread.sleep(GAP_MS)
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally {
                running.set(false)
                val text = if (failed == 0) "Sent to $sent people" else "Sent to $sent, $failed failed"
                notify(app, list, text, ongoing = false, progress = -1)
                Log.i(TAG, "broadcast done: $sent sent, $failed failed")
            }
        }
        return true
    }

    /** Waits for SmsSentReceiver to move [row] out of the outbox; true
     *  when it ended up sent (or the carrier is slow to answer). */
    private fun awaitResult(context: Context, row: android.net.Uri): Boolean {
        val until = System.currentTimeMillis() + WAIT_MS
        while (System.currentTimeMillis() < until) {
            val type = context.contentResolver.query(row, arrayOf(Telephony.Sms.TYPE), null, null, null)
                ?.use { if (it.moveToFirst()) it.getInt(0) else null }
            when (type) {
                Telephony.Sms.MESSAGE_TYPE_FAILED -> return false
                null, Telephony.Sms.MESSAGE_TYPE_OUTBOX, Telephony.Sms.MESSAGE_TYPE_QUEUED -> Thread.sleep(500)
                else -> return true
            }
        }
        return true
    }

    private fun notify(context: Context, list: BroadcastList, text: String, ongoing: Boolean, progress: Int) {
        try {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Broadcast progress", NotificationManager.IMPORTANCE_LOW).apply {
                        setSound(null, null)
                        setShowBadge(false)
                    }
                )
            }
            val open = PendingIntent.getActivity(
                context, NOTIFICATION_ID,
                Intent(context, BroadcastListDetailActivity::class.java).putExtra("list_id", list.id),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification_minimal)
                .setContentTitle("Broadcast: ${list.name}")
                .setContentText(text)
                .setOngoing(ongoing)
                .setOnlyAlertOnce(true)
                .setAutoCancel(!ongoing)
                .setContentIntent(open)
            if (progress >= 0) builder.setProgress(list.contacts.size, progress, false)
            manager.notify(NOTIFICATION_ID, builder.build())
        } catch (e: Exception) {
            Log.w(TAG, "couldn't show broadcast progress", e)
        }
    }
}
