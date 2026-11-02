package com.turbotext.app

import android.content.Context

/**
 * The punctuation picker's order ('1' in T9InputController): the marks
 * picked most come first, so the usual ones are fewest presses away.
 * Marks never or equally used keep [DEFAULT]'s order, which starts with
 * the common ones.
 */
object PunctuationUsage {
    private const val PREFS = "punctuation_usage"

    val DEFAULT = listOf(
        ".", ",", "?", "!", "'", "-", ":", ";", "@", "(", ")", "\"", "/",
        "#", "$", "%", "&", "*", "+", "=", "_", "~", "<", ">"
    )

    /** [DEFAULT] sorted by [counts], most used first; ties keep their
     *  default order (a stable sort). */
    fun order(counts: Map<String, Int>): List<String> =
        DEFAULT.sortedByDescending { counts[it] ?: 0 }

    fun order(context: Context): List<String> {
        val p = prefs(context)
        return order(DEFAULT.associateWith { p.getInt(it, 0) })
    }

    fun recordUse(context: Context, mark: String) {
        val p = prefs(context)
        p.edit().putInt(mark, p.getInt(mark, 0) + 1).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
