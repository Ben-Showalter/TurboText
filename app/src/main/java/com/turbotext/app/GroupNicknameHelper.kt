package com.turbotext.app

import android.content.Context

object GroupNicknameHelper {
    private const val PREFS = "message_pro_group_nicknames"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getNickname(context: Context, threadId: Long): String? =
        prefs(context).getString("nick_$threadId", null)

    fun setNickname(context: Context, threadId: Long, nickname: String) {
        prefs(context).edit().putString("nick_$threadId", nickname).apply()
    }

    fun clearNickname(context: Context, threadId: Long) {
        prefs(context).edit().remove("nick_$threadId").apply()
    }
}
