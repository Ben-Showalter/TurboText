package com.turbotext.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import java.text.DateFormat
import java.util.Date

/**
 * The daily texting report (Settings > Report): how many texts went to
 * and came from each contact since the last report, sent as a text to
 * the number set there, at the time set there.
 *
 *   Boby - 45 sent, 34 received
 *   Sam - 10 sent, 20 received
 *
 * Picture and group messages count too; a group's are listed under the
 * group's name. Texts between this phone and the report number alone
 * are left out, so the report doesn't count itself.
 */
object ReportSender {
    private const val TAG = "TurboTextReport"

    /** How long before a failed report is tried once more. */
    private const val RETRY_DELAY_MS = 15 * 60 * 1000L

    /** First report, or after a long gap: count at most this far back. */
    private const val DEFAULT_WINDOW_MS = 24 * 60 * 60 * 1000L

    /** Counts and sends the report now. Blocks — call off the main
     *  thread. Returns null on success, or what went wrong. */
    fun sendNow(context: Context): String? {
        val number = ReportSettings.getNumber(context) ?: return "No number to send the report to"
        val until = System.currentTimeMillis()
        val last = ReportSettings.getLastSentAt(context)
        val since = if (last > 0) last else until - DEFAULT_WINDOW_MS
        val rows = try {
            count(context, since, until, OwnNumber.key(number))
        } catch (e: Exception) {
            Log.w(TAG, "couldn't count messages", e)
            return "Couldn't read messages"
        }
        val title = "TurboText report, " + DateFormat.getDateInstance(DateFormat.SHORT).format(Date(until)) + ":"
        val body = ReportBuilder.format(title, rows)
        val error = MessageSender.send(context, listOf(number), body, emptyList())
        if (error == null) ReportSettings.setLastSentAt(context, until)
        Log.i(TAG, if (error == null) "report sent (${rows.size} contacts)" else "report not sent: $error")
        return error
    }

    /** Sent and received counts per contact (or group) between [since]
     *  and [until], leaving out [excludeKey] (OwnNumber.key form). */
    private fun count(context: Context, since: Long, until: Long, excludeKey: String): Map<String, ReportBuilder.Counts> {
        val repo = SmsRepository(context)
        val rows = LinkedHashMap<String, ReportBuilder.Counts>()
        val contacts = ContactIndex.load(context)
        val nameByKey = HashMap<String, String>()

        // One row per person, whichever way their number is written.
        fun personLabel(address: String): String? {
            val key = OwnNumber.key(address).ifEmpty { address.lowercase() }
            if (key == excludeKey) return null
            return nameByKey.getOrPut(key) {
                contacts.nameFor(address) ?: ContactHelper.lookupName(context, address)
                    ?: ContactIndex.formatNumber(address)
            }
        }

        // Texts: inbox (1) and sent (2).
        context.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.TYPE),
            "${Telephony.Sms.DATE} > ? AND ${Telephony.Sms.DATE} <= ? AND ${Telephony.Sms.TYPE} IN (1, 2)",
            arrayOf(since.toString(), until.toString()), null
        )?.use { c ->
            while (c.moveToNext()) {
                val label = personLabel(c.getString(0) ?: continue) ?: continue
                val counts = rows.getOrPut(label) { ReportBuilder.Counts() }
                if (c.getInt(1) == 2) counts.sent++ else counts.received++
            }
        }

        // Picture and group messages: received (inbox, downloaded or not)
        // and sent. Delivery reports aren't messages and are left out.
        val labelByThread = HashMap<Long, String?>()
        fun threadLabel(threadId: Long): String? = labelByThread.getOrPut(threadId) {
            val participants = repo.getThreadParticipants(threadId)
            when {
                participants.isEmpty() -> null
                participants.size == 1 -> personLabel(participants[0])
                else -> GroupNicknameHelper.getNickname(context, threadId)
                    ?: participants.joinToString(", ") { p -> personLabel(p) ?: p }
            }
        }
        context.contentResolver.query(
            Telephony.Mms.CONTENT_URI,
            arrayOf(Telephony.Mms.THREAD_ID, Telephony.Mms.MESSAGE_BOX),
            "${Telephony.Mms.DATE} > ? AND ${Telephony.Mms.DATE} <= ? AND " +
                "${Telephony.Mms.MESSAGE_BOX} IN (1, 2) AND ${Telephony.Mms.MESSAGE_TYPE} IN (128, 130, 132)",
            arrayOf((since / 1000).toString(), (until / 1000).toString()), null
        )?.use { c ->
            while (c.moveToNext()) {
                val label = threadLabel(c.getLong(0)) ?: continue
                val counts = rows.getOrPut(label) { ReportBuilder.Counts() }
                if (c.getInt(1) == Telephony.Mms.MESSAGE_BOX_SENT) counts.sent++ else counts.received++
            }
        }
        return rows
    }

    // ---- Scheduling ----

    /** Sets (or clears) the alarm for the next report, per the settings. */
    fun schedule(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pending = alarmIntent(context, retry = false)
        am.cancel(pending)
        if (!ReportSettings.isEnabled(context) || ReportSettings.getNumber(context) == null) return
        val at = ReportBuilder.nextTrigger(
            System.currentTimeMillis(), ReportSettings.getHour(context), ReportSettings.getMinute(context)
        )
        setAlarm(am, at, pending)
    }

    private fun scheduleRetry(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        setAlarm(am, System.currentTimeMillis() + RETRY_DELAY_MS, alarmIntent(context, retry = true))
    }

    private fun setAlarm(am: AlarmManager, at: Long, pending: PendingIntent) {
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        } catch (e: SecurityException) {
            // Exact alarms not allowed: within a few minutes is fine.
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        }
    }

    private fun alarmIntent(context: Context, retry: Boolean): PendingIntent =
        PendingIntent.getBroadcast(
            context, if (retry) 1 else 0,
            Intent(context, ReportAlarmReceiver::class.java).putExtra(ReportAlarmReceiver.EXTRA_RETRY, retry),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    /** Runs a scheduled (or retried) report and sets up the next one. */
    internal fun onAlarm(context: Context, retry: Boolean) {
        if (!retry) schedule(context) // tomorrow's, whatever happens today
        if (!ReportSettings.isEnabled(context)) return
        val error = sendNow(context)
        if (error != null && !retry) scheduleRetry(context)
    }
}

/** Fires at the report time (and once more, 15 minutes later, if
 *  sending failed). */
class ReportAlarmReceiver : BroadcastReceiver() {
    companion object {
        const val EXTRA_RETRY = "retry"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val app = context.applicationContext
        Thread {
            try {
                ReportSender.onAlarm(app, intent.getBooleanExtra(EXTRA_RETRY, false))
            } catch (e: Exception) {
                Log.w("TurboTextReport", "scheduled report failed", e)
            } finally {
                pending.finish()
            }
        }.start()
    }
}

/** After a restart or an app update: keep running in the background, and
 *  set the report's alarm again (alarms don't survive either). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                BackgroundHost.ensureRunning(context)
                ReportSender.schedule(context.applicationContext)
            }
        }
    }
}
