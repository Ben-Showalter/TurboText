package com.turbotext.app

import android.view.KeyEvent

/** This phone's dedicated Mic/Assistant button — a separate physical
 *  button from the softkeys and Volume Up. It surfaces under different
 *  keycodes depending on context — KEYCODE_F4 (134) when an app has
 *  focus, KEYCODE_SPEAKER_IN (287, not a named KeyEvent constant) when
 *  nothing does — so both are treated as the same trigger. Same button,
 *  same codes, as TurboVoice's TurboVoiceAccessibilityService uses
 *  system-wide; here it's just read directly by the foreground Activity
 *  since these screens already own key events while visible. */
object MicButtonKeyCodes {
    val CODES = setOf(KeyEvent.KEYCODE_F4, 287)
}

/** The camera key, while writing a message: takes a picture. It's
 *  KEYCODE_F3 on the E4810, and on the E4610 a Kyocera-only keycode the
 *  phone names KEYCODE_SOFT_LEFT_LOWER (no Android constant, so it's
 *  matched by the name the phone itself reports). */
object CameraKey {
    private const val E4610_NAME = "KEYCODE_SOFT_LEFT_LOWER"
    private val e4610Code: Int by lazy {
        try { KeyEvent.keyCodeFromString(E4610_NAME) } catch (e: Throwable) { 0 }
    }

    fun matches(keyCode: Int): Boolean =
        keyCode == KeyEvent.KEYCODE_F3 || keyCode == KeyEvent.KEYCODE_CAMERA ||
            (e4610Code > 0 && keyCode == e4610Code) ||
            (try { KeyEvent.keyCodeToString(keyCode) == E4610_NAME } catch (e: Throwable) { false })
}

/**
 * Tells a double tap of the Mic button from a hold. Holding still
 * dictates; two quick taps start an audio message. A tap shorter than
 * [WINDOW_MS] is nothing to transcribe, so it's dropped and remembered:
 * a second press within [WINDOW_MS] of it is the double tap.
 */
class MicTaps {
    companion object { const val WINDOW_MS = 400L }

    private var pressedAt = -1L
    private var shortReleaseAt = -1L
    private var swallowRelease = false

    /** A press: true if it completes a double tap. */
    fun press(now: Long): Boolean {
        if (shortReleaseAt >= 0 && now - shortReleaseAt <= WINDOW_MS) {
            shortReleaseAt = -1
            swallowRelease = true
            pressedAt = -1
            return true
        }
        pressedAt = now
        return false
    }

    enum class Release { TRANSCRIBE, DROP, IGNORE }

    /** A release: transcribe a hold, drop a short tap, ignore a double tap's. */
    fun release(now: Long): Release {
        if (swallowRelease) { swallowRelease = false; return Release.IGNORE }
        if (pressedAt < 0) return Release.IGNORE
        val held = now - pressedAt
        pressedAt = -1
        return if (held >= WINDOW_MS) Release.TRANSCRIBE else { shortReleaseAt = now; Release.DROP }
    }
}
