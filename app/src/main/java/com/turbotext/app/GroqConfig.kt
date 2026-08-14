package com.turbotext.app

import android.content.Context
import android.os.Environment
import java.io.File

/**
 * Paste your free Groq API key below, between the quotes, as a
 * fallback default — or set one at runtime via Settings/remote
 * provisioning without needing to rebuild the app at all.
 * Get one at https://console.groq.com/keys — see the README's
 * "Voice-to-text setup" section for the full walkthrough.
 *
 * Example: const val API_KEY = "gsk_abc123..."
 */
object GroqConfig {
    const val API_KEY = "PUT_YOUR_GROQ_API_KEY_HERE"

    /** Same file/folder TurboVoice reads and writes
     *  (Internal storage/Turbo Key/groq_api_key.txt) — a plaintext file at
     *  the shared external storage root, browsable in a file manager, so
     *  the same key can be dropped in once and used by both apps. */
    private const val FOLDER_NAME = "Turbo Key"
    private const val FILE_NAME = "groq_api_key.txt"

    fun keyFile(): File =
        File(File(Environment.getExternalStorageDirectory(), FOLDER_NAME), FILE_NAME)

    fun ensureKeyFileExists() {
        try {
            val file = keyFile()
            if (!file.exists()) {
                file.parentFile?.mkdirs()
                file.createNewFile()
            }
        } catch (e: Exception) {
            // Storage permission not granted yet, or some other
            // filesystem hiccup — not fatal.
        }
    }

    private fun getFileApiKey(): String? =
        try {
            keyFile().readText().trim().ifBlank { null }
        } catch (e: Exception) {
            null
        }

    /** The Turbo Key file if it has a key in it (shared with TurboVoice),
     *  else the runtime-configured SharedPreferences override (set via
     *  remote provisioning before this file-based approach existed),
     *  else the hardcoded fallback above. */
    fun getApiKey(context: Context): String {
        return getFileApiKey() ?: SettingsHelper.getGroqApiKeyOverride(context) ?: API_KEY
    }
}
