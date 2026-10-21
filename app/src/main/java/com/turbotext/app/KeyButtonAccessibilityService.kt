package com.turbotext.app

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent

/**
 * The "Background Helper". Android keeps an enabled accessibility service
 * bound, so TurboText's process stays alive (and is restarted after being
 * killed) with no foreground service and no "running" notification — the
 * same arrangement as MatChat's background helper (its docs/adr/0008).
 * That keeps message alerts, delivery updates, the cover screen and the
 * daily report on time.
 *
 * It does nothing else: it reads no screen content and no keys. When it's
 * off, BackgroundHost runs KeepAliveService instead, which needs a
 * notification. The user turns it on in Settings > Accessibility; the
 * class keeps its old name so phones that already had it on keep it on.
 */
class KeyButtonAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        BackgroundHost.ensureRunning(this, helperRunning = true)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        BackgroundHost.ensureRunning(this, helperRunning = false)
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}
}
