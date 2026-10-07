package com.turbotext.app

import android.content.Context

/**
 * The Emoji mode list: 50 emojis, the ones you use most first.
 *
 * Base order is the Unicode Consortium's published emoji-frequency
 * ranking (top 46 — the tail of that order is approximate), followed by
 * the four TurboText already offered that aren't in it. Every insert
 * bumps a per-emoji count; the list is sorted by that count, with ties
 * (including everything never used) keeping the base order.
 */
object EmojiUsage {

    private const val PREFS = "emoji_usage"

    val BASE = listOf(
        "😂", "❤️", "🤣", "👍", "😭", "🙏", "😘", "🥰", "😍", "😊",
        "🎉", "😁", "💕", "🥺", "😅", "🔥", "☺️", "🤦", "♥️", "🤷",
        "🙄", "😆", "🤗", "😉", "🎂", "🤔", "👏", "🙂", "😳", "🥳",
        "😎", "👌", "💜", "😔", "💪", "✨", "💖", "👀", "😋", "😏",
        "😢", "👉", "💗", "😩", "💯", "🌹",
        "😀", "😡", "👎", "✅"
    )

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** [BASE], most-used first. Stable sort, so unused ones keep their order. */
    fun ordered(context: Context): List<String> {
        val p = prefs(context)
        return BASE.sortedByDescending { p.getInt(it, 0) }
    }

    fun recordUse(context: Context, emoji: String) {
        val p = prefs(context)
        p.edit().putInt(emoji, p.getInt(emoji, 0) + 1).apply()
    }
}
