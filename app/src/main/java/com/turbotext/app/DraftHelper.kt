package com.turbotext.app

import android.content.Context

/**
 * Saves an in-progress, unsent compose text so it's there when you come
 * back to a conversation — keyed by the recipient's address (not thread
 * id, since a brand-new conversation doesn't have one yet).
 */
object DraftHelper {
    private const val PREFS = "message_pro_drafts"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun saveDraft(context: Context, address: String, text: String) {
        if (address.isEmpty()) return
        if (text.isEmpty()) {
            clearDraft(context, address)
        } else {
            prefs(context).edit().putString(address, text).apply()
        }
    }

    fun getDraft(context: Context, address: String): String? =
        prefs(context).getString(address, null)

    fun clearDraft(context: Context, address: String) {
        prefs(context).edit().remove(address).apply()
    }

    fun hasDraft(context: Context, address: String): Boolean =
        prefs(context).contains(address)
}
