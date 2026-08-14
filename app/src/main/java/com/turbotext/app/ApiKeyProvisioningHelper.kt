package com.turbotext.app

import android.content.Context
import android.util.Log

/** Lets the Groq API key be set remotely via a specially-formatted text
 *  message, rather than requiring the app to be rebuilt in Android
 *  Studio every time the key needs to change — useful for a device
 *  that's already been handed to someone non-technical.
 *
 *  Deliberately restricted: nothing happens unless a trusted number has
 *  already been set locally first (Advanced Settings), and even then,
 *  only a message from THAT exact number, in the exact expected format,
 *  is treated as a provisioning command. Without that restriction, any
 *  incoming text could silently rewrite a live, billable API key.
 *
 *  The key is base64-encoded in the message, not encrypted — this is
 *  light obfuscation (so it doesn't just sit as obviously-a-key plain
 *  text in the SMS history) rather than real security. SMS itself isn't
 *  a secure transport; anyone who could intercept the message could
 *  decode it trivially. This is only as safe as trusting the phone and
 *  network the provisioning text actually comes from. */
object ApiKeyProvisioningHelper {
    private const val TAG = "TurboTextProvisioning"
    private const val PREFIX = "TURBOTEXT_SETUP:"

    /** Returns true if this message was a valid provisioning command
     *  (and should be swallowed entirely — not shown as a normal
     *  message, not inserted into the inbox), false otherwise. */
    fun tryHandleProvisioningMessage(context: Context, sender: String, body: String): Boolean {
        val trimmedBody = body.trim()
        if (!trimmedBody.startsWith(PREFIX)) return false

        val trustedNumber = SettingsHelper.getTrustedProvisioningNumber(context)
        if (trustedNumber.isNullOrBlank()) {
            Log.w(TAG, "provisioning-formatted message received but no trusted number is configured — ignoring, treating as a normal message")
            return false
        }
        if (!numbersMatch(sender, trustedNumber)) {
            Log.w(TAG, "provisioning-formatted message received from an untrusted number — ignoring, treating as a normal message")
            return false
        }

        val encoded = trimmedBody.removePrefix(PREFIX).trim()
        val decoded = try {
            String(android.util.Base64.decode(encoded, android.util.Base64.DEFAULT), Charsets.UTF_8).trim()
        } catch (e: Exception) {
            Log.w(TAG, "provisioning message from the trusted number, but couldn't decode the key", e)
            return true
        }
        if (decoded.isEmpty()) return true

        // Written straight into the same Turbo Key file TurboVoice reads
        // from, so one provisioning text keeps both apps' keys in sync.
        // The SharedPreferences override remains as a fallback for
        // devices where "All files access" hasn't been granted yet —
        // GroqConfig.getApiKey() only falls back to it when the file
        // itself is empty/unreadable.
        try {
            GroqConfig.ensureKeyFileExists()
            GroqConfig.keyFile().writeText(decoded)
            Log.i(TAG, "API key updated via remote provisioning (Turbo Key file)")
        } catch (e: Exception) {
            Log.w(TAG, "couldn't write Turbo Key file, falling back to app-local storage", e)
            SettingsHelper.setGroqApiKeyOverride(context, decoded)
        }
        NotificationHelper.showProvisioningConfirmation(context)
        return true
    }

    /** Loose match — incoming sender numbers can be formatted many ways
     *  (+1 prefix, dashes, parens) compared to however the trusted
     *  number was typed into Settings. */
    private fun numbersMatch(a: String, b: String): Boolean {
        fun digitsOnly(s: String) = s.filter { it.isDigit() }.takeLast(10)
        val da = digitsOnly(a)
        val db = digitsOnly(b)
        return da.isNotEmpty() && da == db
    }
}
