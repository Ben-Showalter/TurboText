package com.turbotext.app

import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Human dates for message bubbles and the conversation list:
 * today → time only, yesterday → "Yesterday", within 5 days → weekday,
 * older → the date (with the year when it isn't this year).
 */
object DateLabels {

    private const val RECENT_DAYS = 5

    // DateFormat isn't thread-safe; the public functions are
    // @Synchronized so these can be shared instead of rebuilt per row.
    private val time: DateFormat = DateFormat.getTimeInstance(DateFormat.SHORT)
    private val weekday = SimpleDateFormat("EEE", Locale.getDefault())
    private val monthDay = SimpleDateFormat("MMM d", Locale.getDefault())
    private val monthDayYear = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
    private val shortDate: DateFormat = DateFormat.getDateInstance(DateFormat.SHORT)

    /** Whole calendar days between [millis] and today (0 = today). */
    private fun daysAgo(millis: Long): Int {
        val then = Calendar.getInstance().apply { timeInMillis = millis; startOfDay() }
        val now = Calendar.getInstance().apply { startOfDay() }
        return ((now.timeInMillis - then.timeInMillis) / (24L * 60 * 60 * 1000)).toInt()
    }

    private fun Calendar.startOfDay() {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }

    private fun sameYear(millis: Long): Boolean =
        Calendar.getInstance().apply { timeInMillis = millis }.get(Calendar.YEAR) ==
            Calendar.getInstance().get(Calendar.YEAR)

    /** Under a message: "3:45 PM", "Yesterday 3:45 PM", "Mon 3:45 PM",
     *  "Sep 16, 3:45 PM" or "Sep 16, 2025, 3:45 PM". */
    @Synchronized
    fun forMessage(millis: Long): String {
        val t = time.format(millis)
        val days = daysAgo(millis)
        return when {
            days <= 0 -> t
            days == 1 -> "Yesterday $t"
            days <= RECENT_DAYS -> "${weekday.format(millis)} $t"
            sameYear(millis) -> "${monthDay.format(millis)}, $t"
            else -> "${monthDayYear.format(millis)}, $t"
        }
    }

    /** Conversation list (short): "3:45 PM", "Yesterday", "Mon", "9/16/26". */
    @Synchronized
    fun forList(millis: Long): String {
        if (millis <= 0) return ""
        val days = daysAgo(millis)
        return when {
            days <= 0 -> time.format(millis)
            days == 1 -> "Yesterday"
            days <= RECENT_DAYS -> weekday.format(millis)
            else -> shortDate.format(millis)
        }
    }
}
