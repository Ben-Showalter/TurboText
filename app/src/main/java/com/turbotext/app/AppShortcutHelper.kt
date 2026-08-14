package com.turbotext.app

import android.content.Context

object AppShortcutHelper {
    private const val PREFS = "message_pro_app_shortcut"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun setChosenApp(context: Context, packageName: String, label: String) {
        prefs(context).edit()
            .putString("package", packageName)
            .putString("label", label)
            .apply()
    }

    fun getChosenPackage(context: Context): String? = prefs(context).getString("package", null)

    fun getChosenLabel(context: Context): String? = prefs(context).getString("label", null)
}
