package com.turbotext.app

/**
 * Reactions as this phone can show them, and the emoji it offers to
 * react with. Other phones react with any emoji, including newer ones
 * (🫶, 🥹, skin tones, joined ones like ❤️‍🔥) that this phone's font
 * can't draw: those showed as a box with an X. [canDraw] is
 * EmojiUsage.drawable on the phone; tests pass their own.
 */
object ReactionDisplay {

    /** The zero-width joiner that glues emoji like ❤️‍🔥 together. */
    private const val ZWJ = 0x200D.toChar()

    /** Shown when nothing closer can be drawn; every Android font has it. */
    const val FALLBACK = "♥"

    /** Newer emoji → a close older one (keys without colour selectors). */
    private val SIMILAR = mapOf(
        "🫶" to "❤️", "🩷" to "❤️", "🩵" to "💙", "🩶" to "🖤",
        "🥹" to "😢", "🫠" to "😅", "🥲" to "🙂", "🫡" to "👍",
        "🫣" to "😮", "🫢" to "😮", "🤌" to "👌", "🥳" to "🎉",
        "🫤" to "😕", "🥺" to "😢", "🤩" to "😍", "🥰" to "😍"
    )

    /**
     * What to draw for [emoji]: itself if the phone can draw it; else its
     * plain form (no skin tone or colour selector, first joined part);
     * else a close older emoji; else [FALLBACK]. Never a box.
     */
    fun shown(emoji: String, canDraw: (String) -> Boolean): String {
        if (canDraw(emoji)) return emoji
        val pieces = emoji.split(ZWJ).filter { it.isNotEmpty() }
        for (p in pieces) {
            if (canDraw(p)) return p
            val q = plain(p)
            if (q.isNotEmpty() && canDraw(q)) return q
        }
        for (p in listOf(plain(emoji)) + pieces.map(::plain)) {
            SIMILAR[p]?.let { if (canDraw(it)) return it }
        }
        return FALLBACK
    }

    /** [emoji] without skin tones and colour/text selectors. */
    private fun plain(emoji: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < emoji.length) {
            val cp = emoji.codePointAt(i)
            i += Character.charCount(cp)
            if (cp == 0xFE0F || cp == 0xFE0E || cp in 0x1F3FB..0x1F3FF) continue
            sb.appendCodePoint(cp)
        }
        return sb.toString()
    }

    /** How many of the Recent emoji the React list starts with. */
    private const val RECENT_IN_LIST = 12

    /**
     * The React list: [mine] (my current reaction, marked ✓ by the
     * caller) first, then up to 12 of [recent] (the compose box's Recent
     * emoji, most used first), then the standard ones; each once, and only
     * ones the phone can draw.
     */
    fun choices(recent: List<String>, mine: String?, canDraw: (String) -> Boolean): List<String> =
        (listOfNotNull(mine) + recent.take(RECENT_IN_LIST) + ReactionParser.PICKER_EMOJI)
            .distinct()
            .filter(canDraw)
}
