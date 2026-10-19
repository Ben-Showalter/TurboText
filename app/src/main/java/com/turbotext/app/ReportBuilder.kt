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

    /** "HHMM" or "HH:MM" as typed, to (hour, minute), or null if it isn't
     *  a time. A 3-digit "730" means 7:30. */
    fun parseTime(typed: String): Pair<Int, Int>? {
        val digits = typed.filter { it.isDigit() }
        if (digits.length !in 3..4) return null
        val hour = digits.dropLast(2).toInt()
        val minute = digits.takeLast(2).toInt()
        if (hour > 23 || minute > 59) return null
        return hour to minute
    }

    /** 24-hour "HH:MM". */
    fun timeLabel(hour: Int, minute: Int): String = "%02d:%02d".format(hour, minute)
}
