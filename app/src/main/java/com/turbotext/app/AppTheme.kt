package com.turbotext.app

/** Every color role actually used somewhere in the app. surface2 covers
 *  both "row selection highlight" and "incoming message bubble fill" —
 *  they were already the same hardcoded value (#333333) before theming
 *  existed, and visually serve the same purpose (a surface lifted one
 *  step off the base background), so sharing one role is a coherent
 *  choice rather than a shortcut. bubbleText is separate from
 *  textPrimary because a light theme's message bubbles need dark text
 *  even though the rest of the UI has dark text on a light background
 *  in a different sense (bubbles keep their own fill colors regardless
 *  of overall theme lightness, at least for outgoing bubbles). */
enum class AppTheme(
    val id: String,
    val label: String,
    // Drives which color the native EditText cursor uses (see
    // ThemeHelper.applyCursorColor) — black on light themes, white on
    // dark ones, rather than trying to infer it from textPrimary.
    val isLight: Boolean,
    val background: Int,
    val surface: Int,
    val surface2: Int,
    val textPrimary: Int,
    val textSecondary: Int,
    val textTertiary: Int,
    val accent: Int,
    val accentLight: Int,
    val bubbleIncoming: Int,
    val bubbleOutgoing: Int,
    val bubbleText: Int,
    val fieldBackground: Int,
    val positiveGreen: Int
) {
    MIDNIGHT(
        "midnight", "Dark",
        isLight = false,
        background = 0xFF0D1117.toInt(),
        surface = 0xFF161B22.toInt(),
        surface2 = 0xFF21262D.toInt(),
        textPrimary = 0xFFE6EDF3.toInt(),
        textSecondary = 0xFF8B949E.toInt(),
        textTertiary = 0xFF9AA5B1.toInt(),
        accent = 0xFF58A6FF.toInt(),
        accentLight = 0xFF79C0FF.toInt(),
        bubbleIncoming = 0xFF21262D.toInt(),
        bubbleOutgoing = 0xFF1F3A5F.toInt(),
        bubbleText = 0xFFE6EDF3.toInt(),
        fieldBackground = 0xFF0D1117.toInt(),
        positiveGreen = 0xFF7EE787.toInt()
    ),
    WARM_LIGHT(
        "warm_light", "Light",
        isLight = true,
        background = 0xFFFAF3E8.toInt(),
        surface = 0xFFEFE4D0.toInt(),
        surface2 = 0xFFE4D5BC.toInt(),
        textPrimary = 0xFF3B2F26.toInt(),
        textSecondary = 0xFF7A6C5D.toInt(),
        textTertiary = 0xFF9C8B75.toInt(),
        accent = 0xFFB5651D.toInt(),
        accentLight = 0xFFD08A50.toInt(),
        bubbleIncoming = 0xFFE4D5BC.toInt(),
        bubbleOutgoing = 0xFFD9C2A0.toInt(),
        bubbleText = 0xFF3B2F26.toInt(),
        fieldBackground = 0xFFF5EBDA.toInt(),
        positiveGreen = 0xFF6B8E23.toInt()
    );

    companion object {
        /** Only Dark (Midnight) and Light (Warm) are left; the removed
         *  Classic themes map to the nearest of the two. */
        fun fromId(id: String): AppTheme = when (id) {
            "classic_light" -> WARM_LIGHT
            else -> values().find { it.id == id } ?: MIDNIGHT
        }
    }
}
