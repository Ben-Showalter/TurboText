package com.turbotext.app

import android.content.Context

object SettingsHelper {
    private const val PREFS = "message_pro_settings"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Keys left behind by the removed read-aloud feature. Cleared once
     *  at startup so they don't linger in the prefs file forever. */
    private val REMOVED_KEYS = listOf(
        "read_aloud_mode", "read_aloud_voice", "tts_engine",
        "native_tts_voice", "native_tts_rate", "native_tts_engine_package"
    )

    fun clearRemovedSettings(context: Context) {
        val p = prefs(context)
        if (REMOVED_KEYS.none { p.contains(it) }) return
        val e = p.edit()
        REMOVED_KEYS.forEach { e.remove(it) }
        e.apply()
    }

    fun isSignatureEnabled(context: Context): Boolean =
        prefs(context).getBoolean("signature_enabled", false)

    fun getSignatureText(context: Context): String =
        prefs(context).getString("signature_text", "") ?: ""

    fun setSignature(context: Context, enabled: Boolean, text: String) {
        prefs(context).edit()
            .putBoolean("signature_enabled", enabled)
            .putString("signature_text", text)
            .apply()
    }

    /** Appends the signature to outgoing text, if enabled — used at
     *  send-time in ConversationActivity/ComposeActivity. */
    fun applySignature(context: Context, body: String): String {
        if (!isSignatureEnabled(context)) return body
        val sig = getSignatureText(context)
        if (sig.isEmpty()) return body
        return if (body.isEmpty()) sig else "$body\n$sig"
    }

    /** null means "No Sound". */
    fun getNotificationSoundPath(context: Context): String? =
        prefs(context).getString("notif_sound_path", null)

    fun setNotificationSoundPath(context: Context, path: String?) {
        prefs(context).edit().putString("notif_sound_path", path).apply()
    }

    /** null means "Just Once" (no repeat); otherwise the repeat interval
     *  in minutes for persistent alerts. */
    fun getRepeatMinutes(context: Context): Int? {
        val v = prefs(context).getInt("notif_repeat_minutes", -1)
        return if (v <= 0) null else v
    }

    fun setRepeatMinutes(context: Context, minutes: Int?) {
        prefs(context).edit().putInt("notif_repeat_minutes", minutes ?: -1).apply()
    }

    /** null means "Off". Otherwise one of VibrationPatterns' ids. */
    fun getVibratePattern(context: Context): String? =
        prefs(context).getString("notif_vibrate_pattern", null)

    fun setVibratePattern(context: Context, patternId: String?) {
        prefs(context).edit().putString("notif_vibrate_pattern", patternId).apply()
    }

    /** Contact photo / initials circle on conversation rows and next to
     *  group-thread senders. On by default. */
    fun isShowAvatars(context: Context): Boolean =
        prefs(context).getBoolean("show_avatars", true)

    fun setShowAvatars(context: Context, show: Boolean) {
        prefs(context).edit().putBoolean("show_avatars", show).apply()
    }

    fun getThemeId(context: Context): String =
        prefs(context).getString("theme_id", "classic_dark") ?: "classic_dark"

    fun setThemeId(context: Context, id: String) {
        prefs(context).edit().putString("theme_id", id).apply()
    }

    /** A runtime-set Groq API key, overriding the hardcoded fallback in
     *  GroqConfig — set either from Settings directly, or via a trusted
     *  provisioning text message (see ApiKeyProvisioningHelper). */
    fun getGroqApiKeyOverride(context: Context): String? =
        prefs(context).getString("groq_api_key_override", null)

    fun setGroqApiKeyOverride(context: Context, key: String?) {
        prefs(context).edit().putString("groq_api_key_override", key).apply()
    }

    /** The one phone number allowed to remotely set the API key via a
     *  specially-formatted text message. Null/unset means the feature is
     *  entirely disabled — this has to be set once, locally, before any
     *  incoming message can be treated as a provisioning command. */
    fun getTrustedProvisioningNumber(context: Context): String? {
        val manual = prefs(context).getString("trusted_provisioning_number", null)
        if (!manual.isNullOrBlank()) return manual
        return ProvisioningConfig.DEFAULT_TRUSTED_NUMBER.takeIf { it.isNotBlank() }
    }

    fun setTrustedProvisioningNumber(context: Context, number: String?) {
        prefs(context).edit().putString("trusted_provisioning_number", number).apply()
    }

    /** When true, GroqVoiceInputHelper routes voice-to-text recording
     *  through a connected Bluetooth headset's mic instead of the phone's
     *  built-in one. Defaults to false (phone mic) — off unless the user
     *  opts in from Advanced settings. */
    fun isBluetoothMicEnabled(context: Context): Boolean =
        prefs(context).getBoolean("voice_input_bluetooth_mic", false)

    fun setBluetoothMicEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("voice_input_bluetooth_mic", enabled).apply()
    }
}
