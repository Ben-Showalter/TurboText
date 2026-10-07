package com.turbotext.app

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.Toast

/** Checks for and opens the system setting that turns on
 *  KeyButtonAccessibilityService (the outer-screen unread pulse). */
object AccessibilityHelper {

    fun isServiceEnabled(context: Context): Boolean {
        return try {
            val on = Settings.Secure.getInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1
            if (!on) return false
            val enabled = Settings.Secure.getString(
                context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            val component = ComponentName(context, KeyButtonAccessibilityService::class.java)
            val names = setOf(component.flattenToString(), component.flattenToShortString())
            enabled.split(':').any { it in names }
        } catch (e: Exception) {
            false
        }
    }

    /** Deep-links to the system's Accessibility list — there's no reliable
     *  cross-OEM way to jump straight to TurboText's own toggle, so a toast
     *  says what to look for. */
    fun openSettings(activity: Activity) {
        try {
            activity.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Toast.makeText(activity, "Find and enable \"TurboText\" in the list", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(activity, "Open Settings > Accessibility > TurboText", Toast.LENGTH_LONG).show()
        }
    }
}
