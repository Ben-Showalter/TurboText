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
    CLASSIC_DARK(
        "classic_dark", "Classic Dark",
        isLight = false,
        background = 0xFF000000.toInt(),
        surface = 0xFF222222.toInt(),
        surface2 = 0xFF333333.toInt(),
        textPrimary = 0xFFFFFFFF.toInt(),
        textSecondary = 0xFF888888.toInt(),
        textTertiary = 0xFFAAAAAA.toInt(),
        accent = 0xFF66CCFF.toInt(),
        accentLight = 0xFF80D8FF.toInt(),
        bubbleIncoming = 0xFF333333.toInt(),
        bubbleOutgoing = 0xFF244A6D.toInt(),
        bubbleText = 0xFFFFFFFF.toInt(),
        fieldBackground = 0xFF1A1A1A.toInt(),
        positiveGreen = 0xFF7CFC00.toInt()
    ),
    MIDNIGHT(
        "midnight", "Midnight Blue",
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
    CLASSIC_LIGHT(
        "classic_light", "Classic Light",
        isLight = true,
        background = 0xFFFFFFFF.toInt(),
        surface = 0xFFF0F0F0.toInt(),
        surface2 = 0xFFE0E0E0.toInt(),
        textPrimary = 0xFF000000.toInt(),
        textSecondary = 0xFF666666.toInt(),
        textTertiary = 0xFF999999.toInt(),
        accent = 0xFF0066CC.toInt(),
        accentLight = 0xFF3399FF.toInt(),
        bubbleIncoming = 0xFFE0E0E0.toInt(),
        bubbleOutgoing = 0xFFCCE5FF.toInt(),
        bubbleText = 0xFF000000.toInt(),
        fieldBackground = 0xFFF5F5F5.toInt(),
        positiveGreen = 0xFF2E8B57.toInt()
    ),
    WARM_LIGHT(
        "warm_light", "Warm Light",
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
        fun fromId(id: String): AppTheme = values().find { it.id == id } ?: CLASSIC_DARK
    }
}
