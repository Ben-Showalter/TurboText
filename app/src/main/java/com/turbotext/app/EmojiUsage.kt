package com.turbotext.app

import android.content.Context

/**
 * The emoji picker's contents: 516 emojis in categories, plus a
 * "Recent" category of the ones you use most.
 *
 * Kept to emojis from Unicode's Emoji 12 or earlier, and on top of that
 * only the ones this phone's font can actually draw ([drawable]): some
 * phones' fonts are older and showed the rest as an empty box. Every
 * insert bumps a per-emoji count, which is what "Recent" is sorted by.
 */
object EmojiUsage {

    private const val PREFS = "emoji_usage"

    /** Left out on purpose (angry, scary, rude or body-part ones), and
     *  kept out of "Recent" too if one was used before they were taken out. */
    val REMOVED = setOf(
        "💀", "👽", "👻", "🤬", "👿", "😈", "😠", "😡", "🤰", "🧚", "💃", "🦵", "💪", "💩", "🤡", "🤮", "🤢", "💋", "🦶", "👅", "👄", "🧠", "👂", "👃", "💅", "🕺", "🧙", "🦸", "🎅", "🤶", "👼", "🤱", "💏", "💑"
    )

    /** Most emojis "Recent" shows — five full rows of the grid. */
    private const val RECENT_MAX = 25

    class Category(val name: String, val emojis: List<String>)

    private fun split(s: String): List<String> = s.trim().split(Regex("\\s+"))

    val CATEGORIES = listOf(
        Category(
            "Smileys", split(
                """
                😀 😃 😄 😁 😆 😅 🤣 😂 🙂 🙃 😉 😊 😇 🥰 😍 🤩 😘 😗 ☺️ 😚
                😙 😋 😛 😜 🤪 😝 🤑 🤗 🤭 🤫 🤔 🤐 🤨 😐 😑 😶 😏 😒 🙄 😬
                🤥 😌 😔 😪 🤤 😴 😷 🤒 🤕 🤧 🥵 🥶 🥴 😵 🤯 🤠 🥳 😎 🤓 🧐
                😕 😟 🙁 ☹️ 😮 😯 😲 😳 🥺 😦 😧 😨 😰 😥 😢 😭 😱 😖 😣 😞
                😓 😩 😫 🥱 😤 🤖 😺 😸
                """
            )
        ),
        Category(
            "Gestures & people", split(
                """
                👍 👎 👌 ✌️ 🤞 🤟 🤘 🤙 👈 👉 👆 👇 ☝️ ✋ 🤚 🖐️ 🖖 👋 🤏 ✍️
                👏 🙌 👐 🤲 🤝 🙏 👀 👁️ 🤳 👶 🧒 👦 👧 🧑 👨 👩 🧓 👴 👵 👱
                🧔 🙋 🙅 🙆 🤷 🤦 🙇 💁 🙎 🙍 🧏 👮 👷 💂 🕵️ 👩‍⚕️ 👨‍🍳 👩‍🎓 👨‍🌾 🤴
                👸 👫 👬 👭 👪 🏃 🚶 🧍 🧎
                """
            )
        ),
        Category(
            "Hearts & feelings", split(
                """
                ❤️ 🧡 💛 💚 💙 💜 🖤 🤍 🤎 💔 ❣️ 💕 💞 💓 💗 💖 💘 💝 💟 ♥️
                💌 💯 💢 💥 💫 💦 💨 💬 💭 💤 🌹 🥀 🎁 🍫 🧸 💐 💍 👑 😻
                """
            )
        ),
        Category(
            "Animals & nature", split(
                """
                🐶 🐱 🐭 🐹 🐰 🦊 🐻 🐼 🐨 🐯 🦁 🐮 🐷 🐸 🐵 🙈 🙉 🙊 🐔 🐧
                🐦 🐤 🦆 🦅 🦉 🐴 🦄 🐝 🦋 🐌 🐞 🐢 🐍 🐙 🐠 🐟 🐬 🐳 🦈 🐊
                🐘 🦒 🐄 🐖 🐑 🐕 🐈 🐿️ 🦌 🐾 🌵 🎄 🌲 🌳 🌴 🌱 🌿 🍀 🍁 🍂
                🌷 🌹 🌺 🌸 🌼 🌻 🌞 🌝 🌛 ⭐ 🌟 ✨ ⚡ 🔥 🌈 ☀️ ⛅ ☁️ 🌧️ ⛈️
                ❄️ ☃️ ⛄ 💧 🌊 🌍 🌙 🌋 🌪️ 🌫️
                """
            )
        ),
        Category(
            "Food & drink", split(
                """
                🍏 🍎 🍐 🍊 🍋 🍌 🍉 🍇 🍓 🍈 🍒 🍑 🥭 🍍 🥥 🥝 🍅 🥑 🥦 🥕
                🌽 🌶️ 🥔 🍞 🥐 🥯 🧀 🥚 🍳 🥓 🥩 🍗 🍖 🌭 🍔 🍟 🍕 🥪 🌮 🌯
                🥗 🍝 🍜 🍲 🍣 🍤 🍦 🍩 🍪 🎂 🍰 🧁 🍫 🍬 🍭 🍿 ☕ 🍵 🥤 🍺
                🍻 🥂 🍷 🥃 🍸 🍹 🧃 🥛 🍼 🧊
                """
            )
        ),
        Category(
            "Activities & things", split(
                """
                ⚽ 🏀 🏈 ⚾ 🎾 🏐 🏉 🎱 🏓 🏸 🏒 ⛳ 🎣 🎿 ⛸️ 🏆 🥇 🥈 🥉 🏅
                🎭 🎨 🎬 🎤 🎧 🎹 🥁 🎷 🎺 🎸 🎻 🎲 🎯 🎳 🎮 🧩 🎉 🎊 🎈 🎁
                🎀 🚗 🚕 🚙 🚌 🚓 🚑 🚒 🚚 🚜 🚲 🛵 🏍️ ✈️ 🚀 ⛵ 🚤 🏠 🏡 🏫
                🏥 ⛪ 🏖️ 🏕️ 📱 💻 ⌚ 📷 📺 ⏰ 💡 💰 💵 💳 ✉️ 📦 📝 📌 📎 ✂️
                🔑 🔒 🛒 📚 💊 🩹 🧹 🛏️ 🚿 🧸
                """
            )
        ),
        Category(
            "Symbols", split(
                """
                ✅ ☑️ ✔️ ❌ ❎ ➕ ➖ ➗ ✖️ ❓ ❗ ‼️ ⁉️ 💲 ♻️ ⚠️ 🚫 ⛔ 🔞 🆗
                🆕 🆒 🆓 🔝 🔜 🔙 ➡️ ⬅️ ⬆️ ⬇️ ↩️ 🔄 ▶️ ⏸️ ⏹️ ⏭️ 🔀 🔁 🔔 🔕
                🎵 🎶 🔊 🔇 📣 🔴 🟠 🟡 🟢 🔵 🟣 ⚫ ⚪ 🟥 🟩 🟦 ⬛ ⬜ 🔶 🔷
                🏁 🚩 🏳️ 🏴 🇺🇸 ☮️ ✝️ ☯️ ♾️ 🆘
                """
            )
        ),
    )

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The emojis used so far, most-used first (ties in the order they
     *  appear in [CATEGORIES]). Empty until the first one is used. */
    fun recent(context: Context): List<String> {
        val p = prefs(context)
        val all = CATEGORIES.flatMap { it.emojis }.distinct()
        // Anything used before the list was reorganised still counts.
        val extra = p.all.keys.filter { it !in all && it !in REMOVED }
        return (all + extra)
            .filter { p.getInt(it, 0) > 0 && drawable(it) }
            .sortedByDescending { p.getInt(it, 0) }
            .take(RECENT_MAX)
    }

    /** "Recent" (when there is anything), then every category. */
    fun categories(context: Context): List<Category> {
        val recent = recent(context)
        val shown = CATEGORIES.map { c -> Category(c.name, c.emojis.filter(::drawable)) }
            .filter { it.emojis.isNotEmpty() }
        return if (recent.isEmpty()) shown else listOf(Category("Recent", recent)) + shown
    }

    private val glyphCache = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    private val glyphPaint by lazy { android.graphics.Paint() }

    /** True when the system font has an image for [emoji] (checked once
     *  each). If the check itself fails, the emoji is kept. */
    fun drawable(emoji: String): Boolean = glyphCache.getOrPut(emoji) {
        try {
            synchronized(glyphPaint) { glyphPaint.hasGlyph(emoji) }
        } catch (e: Throwable) {
            true
        }
    }

    fun recordUse(context: Context, emoji: String) {
        val p = prefs(context)
        p.edit().putInt(emoji, p.getInt(emoji, 0) + 1).apply()
    }
}
