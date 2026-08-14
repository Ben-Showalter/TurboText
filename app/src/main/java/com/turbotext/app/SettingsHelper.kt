package com.turbotext.app

import android.content.Context

object SettingsHelper {
    private const val PREFS = "message_pro_settings"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

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

    /** "always", "bluetooth", or "never" — defaults to never. */
    fun getReadAloudMode(context: Context): String =
        prefs(context).getString("read_aloud_mode", "never") ?: "never"

    fun setReadAloudMode(context: Context, mode: String) {
        prefs(context).edit().putString("read_aloud_mode", mode).apply()
    }

    /** One of Groq's Orpheus English voices: autumn, diana, hannah,
     *  austin, daniel, troy. Defaults to "austin" — used in Groq's own
     *  documentation examples as a fairly neutral-sounding default. */
    fun getReadAloudVoice(context: Context): String =
        prefs(context).getString("read_aloud_voice", "austin") ?: "austin"

    fun setReadAloudVoice(context: Context, voice: String) {
        prefs(context).edit().putString("read_aloud_voice", voice).apply()
    }

    fun getThemeId(context: Context): String =
        prefs(context).getString("theme_id", "classic_dark") ?: "classic_dark"

    fun setThemeId(context: Context, id: String) {
        prefs(context).edit().putString("theme_id", id).apply()
    }

    /** "cloud" (Groq) or "native" (on-device Android TTS). Defaults to
     *  cloud, since it's the proven-working option on this device. */
    fun getTtsEngine(context: Context): String =
        prefs(context).getString("tts_engine", "cloud") ?: "cloud"

    fun setTtsEngine(context: Context, engine: String) {
        prefs(context).edit().putString("tts_engine", engine).apply()
    }

    fun getNativeVoiceName(context: Context): String? =
        prefs(context).getString("native_tts_voice", null)

    fun setNativeVoiceName(context: Context, name: String?) {
        prefs(context).edit().putString("native_tts_voice", name).apply()
    }

    fun getNativeSpeechRate(context: Context): Float =
        prefs(context).getFloat("native_tts_rate", 1.0f)

    fun setNativeSpeechRate(context: Context, rate: Float) {
        prefs(context).edit().putFloat("native_tts_rate", rate).apply()
    }

    /** Package name of a specific TTS engine to use (e.g. eSpeak-NG,
     *  RHVoice), overriding whatever the phone's system-wide default is.
     *  Null means "use the system default". */
    fun getNativeTtsEnginePackage(context: Context): String? =
        prefs(context).getString("native_tts_engine_package", null)

    fun setNativeTtsEnginePackage(context: Context, packageName: String?) {
        prefs(context).edit().putString("native_tts_engine_package", packageName).apply()
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
