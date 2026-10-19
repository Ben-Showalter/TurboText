package com.turbotext.app

import android.content.Context

/**
 * The emoji picker's contents: 550 emojis in categories, plus a
 * "Recent" category of the ones you use most.
 *
 * Kept to emojis from Unicode's Emoji 12 or earlier so they draw on this
 * phone's system font; anything newer would show as an empty box. Every
 * insert bumps a per-emoji count, which is what "Recent" is sorted by.
 */
object EmojiUsage {

    private const val PREFS = "emoji_usage"

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
                🤥 😌 😔 😪 🤤 😴 😷 🤒 🤕 🤢 🤮 🤧 🥵 🥶 🥴 😵 🤯 🤠 🥳 😎
                🤓 🧐 😕 😟 🙁 ☹️ 😮 😯 😲 😳 🥺 😦 😧 😨 😰 😥 😢 😭 😱 😖
                😣 😞 😓 😩 😫 🥱 😤 😡 😠 🤬 😈 👿 💀 💩 🤡 👻 👽 🤖 😺 😸
                """
            )
        ),
        Category(
            "Gestures & people", split(
                """
                👍 👎 👌 ✌️ 🤞 🤟 🤘 🤙 👈 👉 👆 👇 ☝️ ✋ 🤚 🖐️ 🖖 👋 🤏 ✍️
                👏 🙌 👐 🤲 🤝 🙏 💪 🦵 🦶 👂 👃 🧠 👀 👁️ 👅 👄 💅 🤳 👶 🧒
                👦 👧 🧑 👨 👩 🧓 👴 👵 👱 🧔 🙋 🙅 🙆 🤷 🤦 🙇 💁 🙎 🙍 🧏
                👮 👷 💂 🕵️ 👩‍⚕️ 👨‍🍳 👩‍🎓 👨‍🌾 🤴 👸 🎅 🤶 🦸 🧙 🧚 👼 🤰 🤱 💃 🕺
                👫 👬 👭 💏 💑 👪 🏃 🚶 🧍 🧎
                """
            )
        ),
        Category(
            "Hearts & feelings", split(
                """
                ❤️ 🧡 💛 💚 💙 💜 🖤 🤍 🤎 💔 ❣️ 💕 💞 💓 💗 💖 💘 💝 💟 ♥️
                💋 💌 💯 💢 💥 💫 💦 💨 💬 💭 💤 🌹 🥀 🎁 🍫 🧸 💐 💍 👑 😻
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
        val extra = p.all.keys.filter { it !in all }
        return (all + extra)
            .filter { p.getInt(it, 0) > 0 }
            .sortedByDescending { p.getInt(it, 0) }
            .take(RECENT_MAX)
    }

    /** "Recent" (when there is anything), then every category. */
    fun categories(context: Context): List<Category> {
        val recent = recent(context)
        return if (recent.isEmpty()) CATEGORIES else listOf(Category("Recent", recent)) + CATEGORIES
    }

    fun recordUse(context: Context, emoji: String) {
        val p = prefs(context)
        p.edit().putInt(emoji, p.getInt(emoji, 0) + 1).apply()
    }
}
