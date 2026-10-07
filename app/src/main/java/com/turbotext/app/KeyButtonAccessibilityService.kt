package com.turbotext.app

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/** A background app normally can't see hardware key presses at all —
 *  Android only delivers them to whatever app is in the foreground. An
 *  AccessibilityService with canRequestFilterKeyEvents is the one
 *  legitimate exception. It has to be enabled once by the user in
 *  Settings > Accessibility.
 *
 *  What it's for: any key press triggers a brief outer-screen icon pulse
 *  if there's an unread message. Pressing a hardware button while the
 *  phone is closed is what wakes the outer screen (confirmed via
 *  SubLcdManagerService.handleKeyEvent in a real log), so this shows
 *  something right at that moment rather than trying to make anything
 *  persist unattended.
 *
 *  Key events are only observed, never consumed — every key keeps its
 *  normal function. (This service used to also take over the home
 *  screen's right soft key to launch TurboText; that's no longer
 *  needed and has been removed.) */
class KeyButtonAccessibilityService : AccessibilityService() {

    private var lastPulseAt = 0L

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) maybePulse()
        return false
    }

    /** At most one check every 8.5s — key-repeat would otherwise query
     *  the SMS provider on every event. */
    private fun maybePulse() {
        val now = System.currentTimeMillis()
        if (now - lastPulseAt < 8500) return
        lastPulseAt = now

        Thread {
            try {
                if (SmsRepository(this).hasAnyUnread()) {
                    OuterScreenNotifier.pulseFlashing(this)
                }
            } catch (e: Exception) {
                Log.w("TurboTextKeyService", "pulse check failed", e)
            }
        }.start()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}
}
