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