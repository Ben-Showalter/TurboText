package com.turbotext.app

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Telephony
import android.telephony.PhoneStateListener
import android.telephony.ServiceState
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import org.json.JSONObject

/**
 * Texts and picture messages that couldn't go out because the phone had
 * no service. They wait — the bubble says "Waiting for signal" — and are
 * sent again as soon as the phone is back in service, with an alarm as a
 * fallback (in case the service change is missed) at growing intervals.
 * One still waiting after [GIVE_UP_MS] is marked Failed, with a
 * notification.
 *
 * Kept in SharedPreferences, so waiting messages survive the app being
 * closed or the phone restarting.
 */
object SendRetry {
    private const val TAG = "TurboTextRetry"
    private const val PREFS = "send_retry"
    private const val KEY_QUEUE = "queue"
    private const val ACTION_ALARM = "com.turbotext.app.SEND_RETRY"
    private const val CHANNEL_ID = "send_failed"
    private const val NOTIFICATION_ID = 4720

    /** How long a message waits for service before it's marked failed. */
    private const val GIVE_UP_MS = 24 * 60 * 60 * 1000L

    /** Fallback alarm intervals; the last one repeats. */
    private val ALARM_DELAYS_MS = longArrayOf(60_000L, 2 * 60_000L, 5 * 60_000L, 15 * 60_000L, 30 * 60_000L)

    /** Short pause after service returns, so the network settles. */
    private const val SETTLE_MS = 5_000L

    data class Entry(
        val kind: String, // "sms" or "mms"
        val uri: String,
        val since: Long,
        val tries: Int,
        val mmsFile: String? = null,
        val subId: Int = -1,
        val group: Boolean = false
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("kind", kind).put("uri", uri).put("since", since).put("tries", tries)
            .put("file", mmsFile ?: "").put("sub", subId).put("group", group)

        companion object {
            fun from(o: JSONObject) = Entry(
                o.getString("kind"), o.getString("uri"), o.getLong("since"), o.optInt("tries", 0),
                o.optString("file").takeIf { it.isNotEmpty() }, o.optInt("sub", -1), o.optBoolean("group")
            )
        }
    }

    private val lock = Any()
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    @Volatile private var listening = false

    // ---- Queue ----

    private fun load(context: Context): MutableList<Entry> {
        val raw = prefs(context).getString(KEY_QUEUE, "[]")
        return try {
            val a = JSONArray(raw)
            (0 until a.length()).map { Entry.from(a.getJSONObject(it)) }.toMutableList()
        } catch (e: Exception) {
            ArrayList()
        }
    }

    private fun save(context: Context, entries: List<Entry>) {
        prefs(context).edit().putString(KEY_QUEUE, JSONArray(entries.map { it.toJson() }).toString()).apply()
    }

    fun waitingCount(context: Context): Int = synchronized(lock) { load(context).size }

    /** True while [uri] (a content://sms or content://mms row) waits for service. */
    fun isWaiting(context: Context, uri: String): Boolean = synchronized(lock) { load(context).any { it.uri == uri } }

    /** Every waiting picture message's row id — read once per thread load. */
    fun waitingMmsIds(context: Context): Set<Long> = synchronized(lock) {
        load(context).filter { it.kind == "mms" }.mapNotNull { Uri.parse(it.uri).lastPathSegment?.toLongOrNull() }.toSet()
    }

    // ---- Service ----

    /** True when the phone is on a mobile network (or the state can't be
     *  read: then it's assumed, so nothing waits forever by mistake). */
    fun inService(context: Context): Boolean = try {
        val tm = context.getSystemService(TelephonyManager::class.java)
        val state = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) tm?.serviceState else null
        state == null || state.state == ServiceState.STATE_IN_SERVICE
    } catch (e: Exception) {
        true
    }

    /** Listens for the phone getting service back. Call at startup; it
     *  lasts as long as the process (the Background Helper keeps it). */
    fun startListening(context: Context) {
        if (listening) return
        val app = context.applicationContext
        main.post {
            try {
                val tm = app.getSystemService(TelephonyManager::class.java) ?: return@post
                @Suppress("DEPRECATION")
                tm.listen(object : PhoneStateListener() {
                    @Deprecated("Deprecated in Java")
                    override fun onServiceStateChanged(serviceState: ServiceState?) {
                        if (serviceState?.state == ServiceState.STATE_IN_SERVICE && waitingCount(app) > 0) {
                            Log.i(TAG, "phone is back in service")
                            main.postDelayed({ retryAll(app) }, SETTLE_MS)
                        }
                    }
                }, PhoneStateListener.LISTEN_SERVICE_STATE)
                listening = true
            } catch (e: Exception) {
                Log.w(TAG, "couldn't watch for service", e)
            }
        }
        // Anything left waiting from before (the app or phone restarted).
        if (waitingCount(app) > 0) scheduleAlarm(app, 0)
    }

    // ---- Waiting and sending again ----

    /** Parks a text that failed for lack of service. */
    fun waitSms(context: Context, rowUri: Uri) {
        setSmsType(context, rowUri, Telephony.Sms.MESSAGE_TYPE_QUEUED)
        add(context, Entry("sms", rowUri.toString(), System.currentTimeMillis(), 0))
    }

    /** Parks a picture message (its PDU file is kept for the resend). */
    fun waitMms(context: Context, rowUri: Uri, pduFile: String, subId: Int, group: Boolean) {
        add(context, Entry("mms", rowUri.toString(), System.currentTimeMillis(), 0, pduFile, subId, group))
    }

    private fun add(context: Context, entry: Entry) {
        val tries = synchronized(lock) {
            val q = load(context)
            val old = q.firstOrNull { it.uri == entry.uri } ?: previous(entry.uri)
            q.removeAll { it.uri == entry.uri }
            // Keep the original time and count, so a message that keeps
            // failing still gives up after a day.
            val e = if (old != null) entry.copy(since = old.since, tries = old.tries + 1) else entry
            q.add(e)
            save(context, q)
            e.tries
        }
        Log.i(TAG, "${entry.kind} ${Uri.parse(entry.uri).lastPathSegment} waiting for service (try ${tries + 1})")
        refresh(context, entry.uri)
        scheduleAlarm(context, tries)
    }

    /** Sends every waiting message again, if the phone has service. */
    fun retryAll(context: Context) {
        val app = context.applicationContext
        // The service listener and the alarm can fire together.
        if (!retrying.compareAndSet(false, true)) return
        Thread {
            try {
                retryAllNow(app)
            } finally {
                retrying.set(false)
            }
        }.start()
    }

    private val retrying = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun retryAllNow(app: Context) {
        run {
            val queue = synchronized(lock) { load(app) }
            if (queue.isEmpty()) return
            val now = System.currentTimeMillis()
            val expired = queue.filter { now - it.since > GIVE_UP_MS }
            expired.forEach { giveUp(app, it) }
            val live = queue - expired.toSet()
            if (live.isEmpty() || !inService(app)) {
                synchronized(lock) { save(app, load(app).filter { e -> expired.none { it.uri == e.uri } }) }
                if (live.isNotEmpty()) scheduleAlarm(app, live.maxOf { it.tries })
                return
            }
            // Out of the queue now; a send that fails again re-adds it
            // (SmsSentReceiver / MmsSentReceiver), keeping its count.
            synchronized(lock) { save(app, load(app).filter { e -> queue.none { it.uri == e.uri } }) }
            Log.i(TAG, "sending ${live.size} waiting message(s) again")
            for (e in live) {
                // Remembered first, so a new failure keeps the original
                // time and count (see add).
                rememberRetried(app, e)
                try {
                    resend(app, e)
                } catch (ex: Exception) {
                    Log.w(TAG, "resend of ${e.uri} failed", ex)
                    add(app, e)
                }
                Thread.sleep(1_500)
            }
        }
    }

    /** Recently resent entries, so [add] can carry over their age and
     *  count when the resend fails too. */
    private val retried = HashMap<String, Entry>()

    private fun rememberRetried(@Suppress("UNUSED_PARAMETER") context: Context, e: Entry) {
        synchronized(retried) { retried[e.uri] = e }
    }

    /** The waiting entry a fresh failure of [uri] continues, if any. */
    fun previous(uri: String): Entry? = synchronized(retried) { retried.remove(uri) }

    private fun resend(context: Context, e: Entry) {
        val uri = Uri.parse(e.uri)
        when (e.kind) {
            "sms" -> {
                val row = context.contentResolver.query(
                    uri, arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.THREAD_ID, Telephony.Sms.TYPE),
                    null, null, null
                )?.use { c -> if (c.moveToFirst()) Triple(c.getString(0), c.getString(1), c.getLong(2)) to c.getInt(3) else null }
                    ?: return // deleted meanwhile
                val (fields, type) = row
                if (type != Telephony.Sms.MESSAGE_TYPE_QUEUED) return // sent or deleted another way
                setSmsType(context, uri, Telephony.Sms.MESSAGE_TYPE_OUTBOX)
                SmsRepository(context).transmitSms(uri, fields.first ?: return, fields.second ?: "", fields.third)
            }
            "mms" -> {
                val box = context.contentResolver.query(uri, arrayOf(Telephony.Mms.MESSAGE_BOX), null, null, null)
                    ?.use { if (it.moveToFirst()) it.getInt(0) else null }
                if (box != Telephony.Mms.MESSAGE_BOX_OUTBOX) {
                    e.mmsFile?.let { java.io.File(it).delete() }
                    return
                }
                val file = e.mmsFile
                if (file == null || !MmsTransmitter.resend(context, uri, file, e.subId, e.group, 1)) {
                    MmsTransmitter.markFailed(context, uri)
                }
            }
        }
        refresh(context, e.uri)
    }

    private fun giveUp(context: Context, e: Entry) {
        val uri = Uri.parse(e.uri)
        Log.w(TAG, "${e.kind} ${uri.lastPathSegment} gave up after waiting a day for service")
        try {
            if (e.kind == "sms") setSmsType(context, uri, Telephony.Sms.MESSAGE_TYPE_FAILED)
            else {
                MmsTransmitter.markFailed(context, uri)
                e.mmsFile?.let { java.io.File(it).delete() }
            }
        } catch (ex: Exception) {
            Log.w(TAG, "couldn't mark ${e.uri} failed", ex)
        }
        refresh(context, e.uri)
        notifyFailed(context)
    }

    private fun setSmsType(context: Context, uri: Uri, type: Int) {
        try {
            context.contentResolver.update(uri, ContentValues().apply { put(Telephony.Sms.TYPE, type) }, null, null)
        } catch (e: Exception) {
            Log.w(TAG, "couldn't update $uri", e)
        }
    }

    /** Updates the bubble of the thread [uri] is in. */
    private fun refresh(context: Context, uri: String) {
        try {
            val col = if (uri.startsWith("content://sms")) Telephony.Sms.THREAD_ID else Telephony.Mms.THREAD_ID
            val threadId = context.contentResolver.query(Uri.parse(uri), arrayOf(col), null, null, null)
                ?.use { if (it.moveToFirst()) it.getLong(0) else null }
            if (threadId != null) {
                MessageCache.remove(threadId)
                MessageEvents.changed(threadId)
            }
            ProviderChangeTracker.bump()
        } catch (e: Exception) {
            // The bubble catches up the next time the thread loads.
        }
    }

    // ---- Alarm fallback ----

    private fun scheduleAlarm(context: Context, tries: Int) {
        try {
            val delay = ALARM_DELAYS_MS[tries.coerceIn(0, ALARM_DELAYS_MS.size - 1)]
            val pending = PendingIntent.getBroadcast(
                context, NOTIFICATION_ID,
                Intent(context, SendRetryReceiver::class.java).setAction(ACTION_ALARM),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            context.getSystemService(AlarmManager::class.java)?.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + delay, pending
            )
        } catch (e: Exception) {
            Log.w(TAG, "couldn't set the retry alarm", e)
        }
    }

    private fun notifyFailed(context: Context) {
        try {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Messages that couldn't be sent", NotificationManager.IMPORTANCE_DEFAULT)
                )
            }
            val open = PendingIntent.getActivity(
                context, NOTIFICATION_ID, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            manager.notify(
                NOTIFICATION_ID,
                NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_notification_minimal)
                    .setContentTitle("Message not sent")
                    .setContentText("No signal for a day. Open the conversation to try again.")
                    .setAutoCancel(true)
                    .setContentIntent(open)
                    .build()
            )
        } catch (e: Exception) {
            Log.w(TAG, "couldn't show the not-sent notice", e)
        }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/** The fallback alarm, and after a restart: send waiting messages again. */
class SendRetryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        SendRetry.retryAll(context)
    }
}
