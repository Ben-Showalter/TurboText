package com.turbotext.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Calendar

/**
 * Send Later: messages written now and sent at a chosen day and time, at
 * most a week ahead (Options > Send Later while composing).
 *
 * Each one is kept in SharedPreferences, with copies of its attachments
 * under files/scheduled/<id>/ so they're still readable when it goes out.
 * An exact alarm per message fires [ScheduledSendReceiver], which sends it
 * the normal way (PendingSends, so it shows "Sending" and waits for a
 * signal if there's none). Until then it shows in its thread as a
 * "Scheduled" bubble with Send Now / Change Time / Cancel.
 */
object ScheduledSends {
    private const val TAG = "TurboTextScheduled"
    private const val PREFS = "scheduled_sends"
    private const val KEY = "entries"
    const val EXTRA_ID = "scheduled_id"

    /** Bubble ids: negative, far below PendingSends' (-1, -2, …). */
    private const val BUBBLE_ID_BASE = 1_000_000_000_000_000L

    data class Attachment(val mime: String, val name: String, val path: String)

    data class Entry(
        val id: Long,
        val threadId: Long,
        val recipients: List<String>,
        val body: String,
        val attachments: List<Attachment>,
        val at: Long
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("id", id).put("thread", threadId).put("to", JSONArray(recipients))
            .put("body", body).put("at", at)
            .put("att", JSONArray(attachments.map { JSONObject().put("mime", it.mime).put("name", it.name).put("path", it.path) }))

        companion object {
            fun from(o: JSONObject): Entry {
                val to = o.getJSONArray("to")
                val att = o.optJSONArray("att") ?: JSONArray()
                return Entry(
                    o.getLong("id"), o.getLong("thread"),
                    (0 until to.length()).map { to.getString(it) },
                    o.optString("body"),
                    (0 until att.length()).map { i ->
                        val a = att.getJSONObject(i)
                        Attachment(a.getString("mime"), a.getString("name"), a.getString("path"))
                    },
                    o.getLong("at")
                )
            }
        }
    }

    /** Furthest ahead a message can be scheduled, in days. */
    const val MAX_DAYS_AHEAD = 7

    /** [hour24]:[minute] on the day [daysAhead] days after [now]'s day. */
    fun timeOn(now: Long, daysAhead: Int, hour24: Int, minute: Int, zone: java.util.TimeZone = java.util.TimeZone.getDefault()): Long =
        Calendar.getInstance(zone).apply {
            timeInMillis = now
            add(Calendar.DAY_OF_YEAR, daysAhead)
            set(Calendar.HOUR_OF_DAY, hour24)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    private val lock = Any()

    private fun load(context: Context): MutableList<Entry> = try {
        val a = JSONArray(prefs(context).getString(KEY, "[]"))
        (0 until a.length()).map { Entry.from(a.getJSONObject(it)) }.toMutableList()
    } catch (e: Exception) {
        ArrayList()
    }

    private fun save(context: Context, entries: List<Entry>) {
        prefs(context).edit().putString(KEY, JSONArray(entries.map { it.toJson() }).toString()).apply()
    }

    fun get(context: Context, id: Long): Entry? = synchronized(lock) { load(context).firstOrNull { it.id == id } }

    /**
     * Schedules [body]/[attachments] to [recipients] at [at]. Copies the
     * attachments first, so call off the main thread. Returns null if an
     * attachment couldn't be copied.
     */
    fun schedule(
        context: Context, threadId: Long, recipients: List<String>, body: String,
        attachments: List<OutgoingAttachment>, at: Long
    ): Entry? {
        val app = context.applicationContext
        val id = System.currentTimeMillis()
        val dir = File(app.filesDir, "scheduled/$id").apply { mkdirs() }
        val copies = ArrayList<Attachment>()
        for ((i, a) in attachments.withIndex()) {
            val file = File(dir, "$i-${a.name.replace(Regex("[^A-Za-z0-9._-]"), "_")}")
            try {
                when {
                    a.bytes != null -> file.writeBytes(a.bytes)
                    a.uri != null -> app.contentResolver.openInputStream(a.uri)?.use { input ->
                        file.outputStream().use { input.copyTo(it) }
                    } ?: throw java.io.IOException("can't read ${a.uri}")
                    else -> continue
                }
            } catch (e: Exception) {
                Log.w(TAG, "couldn't keep an attachment for later", e)
                dir.deleteRecursively()
                return null
            }
            copies.add(Attachment(a.mime, a.name, file.path))
        }
        val entry = Entry(id, threadId, recipients, body, copies, at)
        synchronized(lock) { save(app, load(app) + entry) }
        setAlarm(app, entry)
        Log.i(TAG, "message $id scheduled for ${java.util.Date(at)}")
        MessageEvents.changed(threadId)
        return entry
    }

    /** Moves [id] to [at]. */
    fun reschedule(context: Context, id: Long, at: Long) {
        val app = context.applicationContext
        val entry = synchronized(lock) {
            val q = load(app)
            val i = q.indexOfFirst { it.id == id }
            if (i < 0) return
            q[i] = q[i].copy(at = at)
            save(app, q)
            q[i]
        }
        setAlarm(app, entry)
        MessageEvents.changed(entry.threadId)
    }

    /** Removes [id] without sending it. Returns it (its text goes back in
     *  the compose box); its attachment copies are kept for that. */
    fun cancel(context: Context, id: Long): Entry? {
        val app = context.applicationContext
        val entry = take(app, id) ?: return null
        app.getSystemService(AlarmManager::class.java)?.cancel(alarmIntent(app, id))
        MessageEvents.changed(entry.threadId)
        return entry
    }

    private fun take(context: Context, id: Long): Entry? = synchronized(lock) {
        val q = load(context)
        val e = q.firstOrNull { it.id == id } ?: return null
        save(context, q.filter { it.id != id })
        e
    }

    /** Sends [id] now (its alarm, or Send Now). [done] runs once it's
     *  handed off or failed. */
    fun send(context: Context, id: Long, done: () -> Unit = {}) {
        val app = context.applicationContext
        val entry = take(app, id)
        if (entry == null) {
            done()
            return
        }
        app.getSystemService(AlarmManager::class.java)?.cancel(alarmIntent(app, id))
        Log.i(TAG, "sending scheduled message $id")
        val attachments = entry.attachments.map { OutgoingAttachment(it.mime, it.name, uri = Uri.fromFile(File(it.path))) }
        PendingSends.send(app, entry.threadId, entry.recipients, entry.body, attachments, onDone = { error ->
            if (error != null) Log.w(TAG, "scheduled message $id failed: $error")
            File(app.filesDir, "scheduled/$id").deleteRecursively()
            done()
        })
    }

    /** The thread's scheduled messages as bubbles, for ConversationActivity. */
    fun bubblesFor(context: Context, threadId: Long): List<Message> = synchronized(lock) {
        load(context).filter { it.threadId == threadId }.map { e ->
            val images = e.attachments.filter { it.mime.startsWith("image/") }.map { Uri.fromFile(File(it.path)).toString() }
            Message(
                id = -(BUBBLE_ID_BASE + e.id),
                address = e.recipients.joinToString(","),
                body = e.body,
                date = e.at,
                isOutgoing = true,
                isMms = e.attachments.isNotEmpty() || e.recipients.size > 1,
                imageUri = images.firstOrNull(),
                imageUris = images,
                sendStatus = "scheduled"
            )
        }
    }

    /** The scheduled entry a bubble from [bubblesFor] stands for. */
    fun idForBubble(message: Message): Long? =
        if (message.sendStatus == "scheduled") -message.id - BUBBLE_ID_BASE else null

    /** After a restart or update, when alarms are gone: set them again,
     *  and send any whose time passed while the phone was off. */
    fun rescheduleAll(context: Context) {
        val app = context.applicationContext
        val now = System.currentTimeMillis()
        val entries = synchronized(lock) { load(app) }
        for (e in entries) {
            if (e.at <= now) send(app, e.id) else setAlarm(app, e)
        }
        // Attachment copies of cancelled messages (kept for their compose
        // box) are cleared once they're a day old.
        val keep = entries.map { it.id.toString() }.toSet()
        File(app.filesDir, "scheduled").listFiles()?.forEach { dir ->
            if (dir.name !in keep && now - dir.lastModified() > 24 * 60 * 60 * 1000L) dir.deleteRecursively()
        }
    }

    private fun setAlarm(context: Context, entry: Entry) {
        try {
            val am = context.getSystemService(AlarmManager::class.java) ?: return
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, entry.at, alarmIntent(context, entry.id))
        } catch (e: Exception) {
            Log.w(TAG, "couldn't set the alarm for ${entry.id}", e)
        }
    }

    private fun alarmIntent(context: Context, id: Long): PendingIntent =
        PendingIntent.getBroadcast(
            context, (id % Int.MAX_VALUE).toInt(),
            Intent(context, ScheduledSendReceiver::class.java)
                .setData(Uri.parse("turbotext://scheduled/$id"))
                .putExtra(EXTRA_ID, id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/** A scheduled message's time has come. */
class ScheduledSendReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(ScheduledSends.EXTRA_ID, -1)
        if (id < 0) return
        // Held until the send is handed off, but never near the system's
        // time limit for a receiver: a video can take a while to shrink,
        // and that carries on regardless.
        val pending = goAsync()
        val finished = java.util.concurrent.atomic.AtomicBoolean(false)
        val finish = { if (finished.compareAndSet(false, true)) pending.finish() }
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ finish() }, 8_000)
        ScheduledSends.send(context, id) { finish() }
    }
}

/** How a scheduled time reads: "today at 3:30 PM", "tomorrow at 3:30 PM",
 *  "Fri 13 at 3:30 PM". */
object ScheduleLabels {
    /** Whole days from [now]'s day to [at]'s day (0 = today). */
    fun daysAhead(at: Long, now: Long = System.currentTimeMillis()): Int {
        fun dayStart(t: Long) = Calendar.getInstance().apply {
            timeInMillis = t
            set(Calendar.HOUR_OF_DAY, 12) // noon: clear of daylight-saving hour shifts
            set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        return Math.round((dayStart(at) - dayStart(now)) / (24 * 60 * 60 * 1000.0)).toInt()
    }

    fun isToday(at: Long, now: Long = System.currentTimeMillis()): Boolean = daysAhead(at, now) == 0

    /** "Today", "Tomorrow", or the weekday and date, e.g. "Fri 13". */
    fun dayName(at: Long, now: Long = System.currentTimeMillis()): String = when (daysAhead(at, now)) {
        0 -> "Today"
        1 -> "Tomorrow"
        else -> java.text.SimpleDateFormat("EEE d", java.util.Locale.getDefault()).format(java.util.Date(at))
    }

    fun whenText(at: Long): String {
        val time = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(at))
        return "${dayName(at).let { if (it == "Today" || it == "Tomorrow") it.lowercase() else it }} at $time"
    }

    /** The bubble's status: "Scheduled", "Scheduled for tomorrow", "Scheduled for Fri 13". */
    fun bubbleStatus(at: Long): String = when (daysAhead(at)) {
        0 -> "Scheduled"
        1 -> "Scheduled for tomorrow"
        else -> "Scheduled for ${dayName(at)}"
    }
}

/**
 * Send Later's one screen: Day, hour, minute and AM/PM side by side
 * (KeypadTimeDialog with a Day column), opening on Tomorrow at the next
 * half hour. The line under it spells out the date and time; a time
 * already past is refused and the screen stays open.
 */
object SendLaterPicker {
    /** The next whole or half hour after [now], as (hour24, minute). */
    fun nextHalfHour(now: Long, zone: java.util.TimeZone = java.util.TimeZone.getDefault()): Pair<Int, Int> {
        val cal = Calendar.getInstance(zone).apply {
            timeInMillis = now
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            val m = get(Calendar.MINUTE)
            add(Calendar.MINUTE, if (m < 30) 30 - m else 60 - m)
        }
        return cal.get(Calendar.HOUR_OF_DAY) to cal.get(Calendar.MINUTE)
    }

    fun pick(activity: android.app.Activity, current: Long?, onPicked: (Long) -> Unit) {
        val now = System.currentTimeMillis()
        val dayMs = 24 * 60 * 60 * 1000L
        val labels = (0..ScheduledSends.MAX_DAYS_AHEAD).map { ScheduleLabels.dayName(now + it * dayMs, now) }
        val (hour, minute) = current?.let {
            val c = Calendar.getInstance().apply { timeInMillis = it }
            c.get(Calendar.HOUR_OF_DAY) to c.get(Calendar.MINUTE)
        } ?: nextHalfHour(now)
        val day = current?.let { ScheduleLabels.daysAhead(it, now).coerceIn(0, labels.size - 1) } ?: 1
        val full = java.text.SimpleDateFormat("EEE, MMM d", java.util.Locale.getDefault())
        val time = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT)
        fun at(d: Int, h: Int, m: Int) = ScheduledSends.timeOn(System.currentTimeMillis(), d, h, m)
        KeypadTimeDialog(
            activity, "Send Later", hour, minute,
            days = labels,
            day = day,
            describe = { d, h, m -> at(d, h, m).let { "${full.format(java.util.Date(it))} at ${time.format(java.util.Date(it))}" } },
            check = { d, h, m -> if (at(d, h, m) <= System.currentTimeMillis()) "That time has passed; pick a later one" else null }
        ) { d, h, m -> onPicked(at(d, h, m)) }.show()
    }
}
