package com.turbotext.app

import java.util.Calendar
import java.util.TimeZone

/** The daily usage report's wording and timing — kept free of Android so
 *  it can be unit-tested. See ReportSender for the counting and sending. */
object ReportBuilder {

    class Counts(var sent: Int = 0, var received: Int = 0) {
        val total get() = sent + received
    }

    /**
     * One line per contact, busiest first, like
     * "Boby - 45 sent, 34 received", under a title line. Contacts with
     * the same total keep the order they were added in.
     */
    fun format(title: String, rows: Map<String, Counts>): String {
        val lines = rows.entries
            .filter { it.value.total > 0 }
            .sortedByDescending { it.value.total }
            .map { (name, c) -> "$name - ${c.sent} sent, ${c.received} received" }
        return if (lines.isEmpty()) "$title\nNo texts sent or received."
        else (listOf(title) + lines).joinToString("\n")
    }

    /** The next time it's [hour]:[minute] in [zone], strictly after [now]
     *  (milliseconds). */
    fun nextTrigger(now: Long, hour: Int, minute: Int, zone: TimeZone = TimeZone.getDefault()): Long {
        val cal = Calendar.getInstance(zone).apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (cal.timeInMillis <= now) cal.add(Calendar.DAY_OF_YEAR, 1)
        return cal.timeInMillis
    }

    /** "8:30 PM". */
    fun timeLabel12(hour: Int, minute: Int): String {
        val h = if (hour % 12 == 0) 12 else hour % 12
        return "%d:%02d %s".format(h, minute, if (hour >= 12) "PM" else "AM")
    }

    /** 1–12 plus AM/PM to 0–23. */
    fun to24Hour(hour12: Int, pm: Boolean): Int = (hour12 % 12) + if (pm) 12 else 0

}
