package com.turbotext.app

import android.accessibilityservice.AccessibilityService
import android.app.ActivityManager
import android.content.Intent
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/** A background app normally can't see hardware key presses at all —
 *  Android only delivers them to whatever app is currently in the
 *  foreground. An AccessibilityService with canRequestFilterKeyEvents
 *  is the one legitimate exception: it can observe (and choose whether
 *  to consume) key events system-wide. Requires the user to manually
 *  enable this once in Settings > Accessibility — there's no way
 *  around that, it's how Android gates this level of access.
 *
 *  Two independent things happen here now:
 *
 *  1. Any key press triggers a brief outer-screen icon pulse if there's
 *     an unread message — pressing a hardware button while the phone is
 *     closed is what actually wakes the outer screen (confirmed via
 *     SubLcdManagerService.handleKeyEvent in a real log), so this shows
 *     something right at that moment rather than trying to make
 *     anything persist unattended.
 *
 *  2. The right soft key specifically, but *only* while looking at
 *     Kyocera Home's actual idle/home screen (HomeScreenActivity, not
 *     MainMenuGridActivity — the app grid shares the same package but
 *     is a different screen and shouldn't be intercepted), launches
 *     TurboText directly and consumes the event. Disassembling
 *     kyocerahome showed its "MESSAGE" shortcut is a flat,
 *     unconditional explicit intent hardcoded to com.android.mms — no
 *     default-app check anywhere in it. A real device log also showed
 *     that shortcut is already broken on its own
 *     (ActivityNotFoundException — the stock Messaging app's activity
 *     is apparently disabled now that TurboText is the default SMS
 *     app), so consuming the key here fixes a crash as well as
 *     redirecting it.
 *
 *     Getting foreground-app detection working took several real
 *     attempts. accessibility window-state events genuinely never fire
 *     for the return-to-Home transition on this hardware (confirmed by
 *     directly logging every TYPE_WINDOW_STATE_CHANGED event received —
 *     zero showed up across several real End-key presses, not just
 *     assumed); rootInActiveWindow always returned null even after
 *     adding flagRetrieveInteractiveWindows (it depends on a window
 *     holding "accessibility focus", which the home screen apparently
 *     never gets here); getWindows() also came back empty.
 *     UsageStatsManager.queryEvents() (matching Button Mapper's use of
 *     the sibling getRecentTasks() API, both gated by GET_TASKS, which
 *     Button Mapper's disassembly showed working on this exact OEM build
 *     despite being locked down for most apps since Android 5.0) looked
 *     right at first, but real device logs showed the opposite problem:
 *     a transition still not visible to a query issued 700ms-1.1s+ after
 *     it actually happened, consistently, across multiple real captures
 *     — that's flush latency in the usage-stats store itself, not
 *     something any query window size can work around.
 *     getRunningTasks() is a genuinely different mechanism from either of
 *     those: a live snapshot of the actual current task stack (topmost
 *     task first), not a stored/flushed log, so it can't have that same
 *     latency by construction. Used as the primary source now, with the
 *     UsageStatsManager read kept only as a logged comparison point.
 *
 *     A real device log also showed a raw startActivity() call made from
 *     here (a background service context) getting silently throttled by
 *     this OEM build — "Activity start request from <uid> stopped" in
 *     Logcat, with the launch not actually completing for several more
 *     seconds. Sending the same Intent via a PendingIntent instead (see
 *     launchTurboText() below) avoids that, matching the fix already
 *     validated in TurboLaunch's own equivalent service.
 *
 *     getRunningTasks() turned out to have its own failure mode, caught
 *     on a real E4810: it's been locked down since Android 5.0 to only
 *     reliably report the caller's own tasks plus the home task to
 *     third-party callers (see ActivityManager.getRunningTasks() docs -
 *     other tasks "can leak personal information to the caller"). A
 *     logged case had com.turbolaunch.app on screen for 6+ seconds
 *     (confirmed by the UsageStatsManager comparison query below) while
 *     getRunningTasks() still reported kyocerahome/HomeScreenActivity -
 *     home is the one thing the OS is willing to hand a third-party app,
 *     so that's what it fell back to instead of the real foreground app.
 *     Not a timing issue, so retrying or delaying doesn't help.
 *     UsageStatsManager doesn't have that restriction (it got this one
 *     right), so it's now used as a veto: if it caught a different app
 *     going foreground within the last few seconds, a Home report from
 *     getRunningTasks() is treated as untrustworthy rather than acted on. */
class KeyButtonAccessibilityService : AccessibilityService() {

    private var lastPulseAt = 0L

    // Tracks whether we consumed the DOWN half of the current SOFT_RIGHT
    // press, so the matching UP gets consumed too — otherwise the system
    // keeps trying to redeliver the orphaned UP event to whatever's
    // taking focus next (the newly-launching MainActivity, mid-startup),
    // logged repeatedly as "Cancelling event due to no window focus".
    private var interceptedSoftRightDown = false

    companion object {
        private const val HOME_SCREEN_CLASS = "jp.kyocera.kyocerahome.HomeScreenActivity"
        private const val HOME_SCREEN_PACKAGE = "jp.kyocera.kyocerahome"

        // getRunningTasks() falls back to reporting Home whenever it can't see the
        // real foreground app, indistinguishable from actually being on Home. This
        // is how long a contradicting UsageStatsManager sighting of another app is
        // trusted to override that Home report: long enough to clear the flush
        // latency measured in earlier debugging (700ms-1.1s+), short enough not to
        // block a deliberate soft-right press after the user has genuinely returned
        // home.
        private const val HOME_VETO_WINDOW_MS = 5000L
    }

    @Suppress("DEPRECATION")
    private fun currentForeground(): Pair<String?, String?> {
        val (runningPackage, runningClass) = try {
            val am = getSystemService(ActivityManager::class.java) ?: return null to null
            val topTask = am.getRunningTasks(1).firstOrNull()?.topActivity
            Log.i("TurboTextKeyService", "getRunningTasks foreground: package=${topTask?.packageName} class=${topTask?.className}")
            topTask?.packageName to topTask?.className
        } catch (e: Exception) {
            Log.w("TurboTextKeyService", "getRunningTasks query failed", e)
            null to null
        }

        // Only worth the extra query when getRunningTasks claims Home — that's the
        // one report it's known to give even when it's wrong (see class doc).
        if (runningClass == HOME_SCREEN_CLASS) {
            val (recentPackage, recentAt) = lastForegroundFromUsageStats()
            val age = System.currentTimeMillis() - recentAt
            if (recentPackage != null && recentPackage != HOME_SCREEN_PACKAGE &&
                recentPackage != packageName && age in 0 until HOME_VETO_WINDOW_MS
            ) {
                Log.i(
                    "TurboTextKeyService",
                    "vetoing getRunningTasks' Home report: UsageStatsManager saw $recentPackage foreground ${age}ms ago"
                )
                return recentPackage to null
            }
        }

        return runningPackage to runningClass
    }

    @Suppress("DEPRECATION")
    private fun lastForegroundFromUsageStats(): Pair<String?, Long> {
        return try {
            val usm = getSystemService(android.app.usage.UsageStatsManager::class.java) ?: return null to -1L
            val end = System.currentTimeMillis()
            val start = end - 24 * 60 * 60 * 1000L
            val events = usm.queryEvents(start, end)
            val event = android.app.usage.UsageEvents.Event()
            var lastPackage: String? = null
            var lastTime = -1L
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.eventType == android.app.usage.UsageEvents.Event.MOVE_TO_FOREGROUND &&
                    event.timeStamp > lastTime
                ) {
                    lastTime = event.timeStamp
                    lastPackage = event.packageName
                }
            }
            Log.i("TurboTextKeyService", "(comparison) UsageStatsManager most recent foreground: package=$lastPackage")
            lastPackage to lastTime
        } catch (e: Exception) {
            Log.w("TurboTextKeyService", "(comparison) UsageStatsManager query failed", e)
            null to -1L
        }
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_SOFT_RIGHT && event.action == KeyEvent.ACTION_UP && interceptedSoftRightDown) {
            interceptedSoftRightDown = false
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN) {
            // currentForeground() does a couple of system_server round
            // trips. onKeyEvent blocks key delivery until it returns, so
            // calling this on *every* key (T9 typing, DPAD nav, ...) made
            // all input feel laggy. Only KEYCODE_SOFT_RIGHT ever needs
            // foregroundClass, so every other key skips straight to
            // maybePulse().
            if (event.keyCode == KeyEvent.KEYCODE_SOFT_RIGHT) {
                val (foregroundPackage, foregroundClass) = currentForeground()
                Log.i(
                    "TurboTextKeyService",
                    "key event: keyCode=${event.keyCode} (${KeyEvent.keyCodeToString(event.keyCode)}) " +
                        "foregroundPackage=$foregroundPackage foregroundClass=$foregroundClass"
                )

                if (foregroundClass == HOME_SCREEN_CLASS) {
                    launchTurboText()
                    interceptedSoftRightDown = true
                    return true // consume — stop kyocerahome's own hardcoded handler from also firing
                }
            }

            maybePulse()
        }
        return false // every other key/context: never consume, normal function preserved
    }

    private fun launchTurboText() {
        try {
            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            // A direct startActivity() call from here measured a multi-second
            // delay before MainActivity actually appeared, even though
            // ActivityManager logged the launch as issued immediately (real
            // device log: "Activity start request from <uid> stopped",
            // followed by the activity only actually resuming several
            // seconds later) — same throttling TurboLaunch's own
            // LauncherAccessibilityService hit and worked around the same
            // way. Going through a PendingIntent instead avoids whatever
            // this OEM's build applies to a raw startActivity() call made
            // from this background service context.
            val pendingIntent = android.app.PendingIntent.getActivity(
                this, 0, intent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            pendingIntent.send()
            Log.i("TurboTextKeyService", "launched TurboText in place of kyocerahome's MESSAGE shortcut")
        } catch (e: Exception) {
            Log.w("TurboTextKeyService", "failed to launch TurboText", e)
        }
    }

    private fun maybePulse() {
        // Debounced to just over the length of one full flash sequence
        // (~8s) — a second key press shouldn't start an overlapping
        // sequence while one's still running.
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

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Diagnostic only for now — not used to make any decision yet.
        // currentForeground()'s UsageStatsManager query has measured real,
        // non-trivial flush latency on this device (a transition still
        // not visible to a query issued 700ms+ after it happened), which
        // window-state accessibility events wouldn't have since they're
        // a direct callback, not a queryable store. But an older comment
        // here claimed these "never fired" for the return-to-Home
        // transition specifically — verified on a *different* Kyocera
        // phone (E4810), never actually confirmed on this E4610. Logging
        // real events here to see what actually happens on this hardware
        // before trusting either claim and wiring it into onKeyEvent.
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            Log.i(
                "TurboTextKeyService",
                "window state changed: package=${event.packageName} class=${event.className}"
            )
        }
    }

    override fun onInterrupt() {}
}
