package com.turbotext.app

import android.content.Context
import java.security.MessageDigest
import java.security.SecureRandom

/** Settings for the daily texting report (ReportSender), and the PIN
 *  that guards them. Only a salted hash of the PIN is stored. */
object ReportSettings {
    private const val PREFS = "report_settings"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(context: Context) = prefs(context).getBoolean("enabled", false)
    fun setEnabled(context: Context, on: Boolean) = prefs(context).edit().putBoolean("enabled", on).apply()

    /** Report time, 24-hour; 20:00 until set. */
    fun getHour(context: Context) = prefs(context).getInt("hour", 20)
    fun getMinute(context: Context) = prefs(context).getInt("minute", 0)
    fun setTime(context: Context, hour: Int, minute: Int) =
        prefs(context).edit().putInt("hour", hour).putInt("minute", minute).apply()

    /** Where the report is texted; null until set. */
    fun getNumber(context: Context): String? = prefs(context).getString("number", null)?.takeIf { it.isNotBlank() }
    fun setNumber(context: Context, number: String?) = prefs(context).edit().putString("number", number).apply()

    /** When the last report was sent (ms) — the next one counts from here. */
    fun getLastSentAt(context: Context) = prefs(context).getLong("last_sent_at", 0L)
    fun setLastSentAt(context: Context, time: Long) = prefs(context).edit().putLong("last_sent_at", time).apply()

    fun hasPin(context: Context) = prefs(context).getString("pin_hash", null) != null

    fun setPin(context: Context, pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        prefs(context).edit()
            .putString("pin_salt", salt.toHex())
            .putString("pin_hash", hash(salt, pin))
            .apply()
    }

    fun checkPin(context: Context, pin: String): Boolean {
        val p = prefs(context)
        val salt = p.getString("pin_salt", null)?.fromHex() ?: return false
        val stored = p.getString("pin_hash", null) ?: return false
        return MessageDigest.isEqual(stored.toByteArray(), hash(salt, pin).toByteArray())
    }

    private fun hash(salt: ByteArray, pin: String): String =
        MessageDigest.getInstance("SHA-256").run {
            update(salt)
            update(pin.toByteArray())
            digest().toHex()
        }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
    private fun String.fromHex() = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
