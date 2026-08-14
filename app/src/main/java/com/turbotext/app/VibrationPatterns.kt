package com.turbotext.app

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator

/** A handful of distinguishable vibration patterns for notifications.
 *  Each is a standard Android vibration timing array: alternating
 *  off/on durations in milliseconds, starting with an initial delay
 *  (0 here, since we want it to start immediately). */
object VibrationPatterns {

    data class Pattern(val id: String, val label: String, val timings: LongArray)

    val all: List<Pattern> = listOf(
        Pattern("short", "Short Buzz", longArrayOf(0, 200)),
        Pattern("long", "Long Buzz", longArrayOf(0, 800)),
        Pattern("double", "Double Buzz", longArrayOf(0, 150, 100, 150)),
        Pattern("pulse", "Pulse", longArrayOf(0, 100, 100, 100, 100, 100, 100, 100)),
        Pattern("sos", "SOS", longArrayOf(0, 120, 80, 120, 80, 120, 200, 300, 80, 300, 80, 300, 200, 120, 80, 120, 80, 120))
    )

    fun byId(id: String?): Pattern? = all.find { it.id == id }

    /** One-shot vibration of an arbitrary duration, for UI feedback that
     *  isn't a user-configurable notification pattern (e.g. "recording
     *  started", "text dropped in"). */
    fun tick(context: Context, durationMs: Long) {
        try {
            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
            if (!vibrator.hasVibrator()) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(durationMs)
            }
        } catch (e: Exception) {
            android.util.Log.w("TurboTextVibrate", "failed to vibrate", e)
        }
    }

    /** Fires a pattern once — used both for the notification-time buzz
     *  and for the settings-screen preview when picking one. repeatOnce
     *  means "don't loop" (index -1 to VibrationEffect/legacy vibrate). */
    fun play(context: Context, patternId: String?) {
        val pattern = byId(patternId) ?: return
        try {
            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
            if (!vibrator.hasVibrator()) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(pattern.timings, -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(pattern.timings, -1)
            }
        } catch (e: Exception) {
            android.util.Log.w("TurboTextVibrate", "failed to vibrate", e)
        }
    }
}
