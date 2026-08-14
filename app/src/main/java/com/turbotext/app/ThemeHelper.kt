package com.turbotext.app

import android.app.Activity
import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView

/** Applies the selected theme by walking a view tree and remapping every
 *  hardcoded color value used anywhere in the app's layouts to the
 *  current theme's equivalent role. This covers static Activity layouts
 *  (call apply(activity) after setContentView) AND RecyclerView rows
 *  (call apply(itemView, context) from onBindViewHolder — rows are
 *  created/recycled after the Activity's own one-time walk, so they
 *  need their own pass each time they're bound).
 *
 *  Programmatically-constructed visuals that don't go through a plain
 *  View background/text color at all — MessageAdapter's LayerDrawable
 *  bubbles being the main example — read theme colors directly via
 *  ThemeHelper.getCurrentTheme() instead of relying on this remap. */
object ThemeHelper {
    // Every exact hardcoded color value used anywhere in the app's XML
    // or Kotlin, mapped to the theme property that should replace it.
    private val backgroundMap = mapOf(
        0xFF000000.toInt() to AppTheme::background,
        0xFF222222.toInt() to AppTheme::surface,
        0xFF333333.toInt() to AppTheme::surface2,
        0xFF1A1A1A.toInt() to AppTheme::fieldBackground
    )
    private val textMap = mapOf(
        0xFFFFFFFF.toInt() to AppTheme::textPrimary,
        0xFF888888.toInt() to AppTheme::textSecondary,
        0xFF666666.toInt() to AppTheme::textSecondary,
        0xFF777777.toInt() to AppTheme::textSecondary,
        0xFF555555.toInt() to AppTheme::textSecondary,
        0xFFAAAAAA.toInt() to AppTheme::textTertiary,
        0xFF66CCFF.toInt() to AppTheme::accent,
        0xFF80D8FF.toInt() to AppTheme::accentLight,
        0xFF7CFC00.toInt() to AppTheme::positiveGreen
    )

    fun getCurrentTheme(context: Context): AppTheme = AppTheme.fromId(SettingsHelper.getThemeId(context))

    fun setTheme(context: Context, theme: AppTheme) {
        SettingsHelper.setThemeId(context, theme.id)
    }

    /** Call after setContentView() in an Activity's onCreate(). */
    fun apply(activity: Activity) {
        val root = activity.findViewById<View>(android.R.id.content)
        walk(root, getCurrentTheme(activity))
    }

    /** Call from an adapter's onBindViewHolder with the row's root view —
     *  RecyclerView rows are created/recycled after the Activity's own
     *  one-time walk, so each bind needs its own pass. */
    fun apply(view: View, context: Context) {
        walk(view, getCurrentTheme(context))
    }

    private fun walk(view: View, theme: AppTheme) {
        remapBackground(view, theme)
        if (view is TextView) {
            remapTextColor(view, theme)
            remapHintColor(view, theme)
        }
        if (view is EditText) applyCursorColor(view, theme)
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) walk(view.getChildAt(i), theme)
        }
    }

    /** Black cursor on light themes, white on dark ones. There's no public
     *  API for this pre-API 29 (EditText.setTextCursorDrawable() only
     *  exists from Q onward, and this app targets phones running much
     *  older Android) — mCursorDrawableRes is the long-standing reflection
     *  workaround, a plain int field on TextView itself (not nested in the
     *  lazily-created Editor), read only when the cursor is first actually
     *  drawn. Setting it here, right after setContentView() and before any
     *  view has taken focus, is early enough for that to land. Best-effort:
     *  if a given OS version renamed/removed the field, the cursor just
     *  falls back to its default theme color instead of crashing. */
    private fun applyCursorColor(view: EditText, theme: AppTheme) {
        try {
            val drawableRes = if (theme.isLight) R.drawable.cursor_black else R.drawable.cursor_white
            val field = TextView::class.java.getDeclaredField("mCursorDrawableRes")
            field.isAccessible = true
            field.set(view, drawableRes)
        } catch (e: Exception) {
            Log.w("ThemeHelper", "couldn't set cursor color via reflection", e)
        }
    }

    private fun remapBackground(view: View, theme: AppTheme) {
        val bg = view.background as? ColorDrawable ?: return
        val property = backgroundMap[bg.color] ?: return
        view.setBackgroundColor(property.get(theme))
    }

    private fun remapTextColor(view: TextView, theme: AppTheme) {
        val property = textMap[view.currentTextColor] ?: return
        view.setTextColor(property.get(theme))
    }

    /** Separate from remapTextColor — a hint (android:textColorHint, or
     *  the system default when that's left unset entirely) is tracked as
     *  its own color independent of the real text color, so it needs its
     *  own lookup/remap rather than piggybacking on the one above. A
     *  hint field with no explicit textColorHint at all resolves to a
     *  system default that's outside textMap and is left alone here —
     *  give it an explicit one that IS in textMap to have it participate. */
    private fun remapHintColor(view: TextView, theme: AppTheme) {
        val property = textMap[view.currentHintTextColor] ?: return
        view.setHintTextColor(property.get(theme))
    }

    /** Sets up focus-based background highlighting for a simple list
     *  row — the system's default focus indicator wasn't visible enough
     *  on this device, especially on light themes. Shared by every
     *  adapter using the plain item_word.xml row template. */
    fun applyRowFocusHighlight(view: View, context: Context) {
        view.setOnFocusChangeListener { v, hasFocus ->
            val theme = getCurrentTheme(context)
            v.setBackgroundColor(if (hasFocus) theme.surface2 else android.graphics.Color.TRANSPARENT)
        }
    }
}
