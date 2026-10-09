package com.turbotext.app

import android.app.Activity
import android.os.Build
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.view.WindowInsetsController

/**
 * Hides the phone's white soft-key label bar at the bottom of the screen.
 * On keypad phones that bar is the system *navigation bar*. The app's main
 * screens draw their own labelled soft-key bar, so the system one just
 * repeats those labels and costs ~26dp of a 320px screen. Hidden on every
 * screen, with no setting to bring it back.
 *
 * Applied to every activity from TurboTextApplication's lifecycle callbacks
 * (on resume, and again whenever the window regains focus) rather than from
 * each screen, since there's no shared base activity. If the system T9
 * keyboard ever brings the bar back while typing, that's left alone: the
 * focus change re-hides it when typing ends.
 *
 * Two layers, always both:
 *  - Android 11+: the insets controller.
 *  - Every API level: the old sticky-immersive flags, on the decor view AND
 *    in the window attributes, because vendor keypad ROMs often honor only
 *    these. The listener puts them back if the system clears them.
 * The window is not extended under the bar, so content just grows into the
 * freed space. If a ROM refuses to hide the bar, the app's own bar sits
 * above it rather than behind it.
 *
 * Adapted from templates/SystemBars.kt in Ben-Showalter/Flip-DumbPhoneGuide.
 */
object SystemBars {

    @Suppress("DEPRECATION")
    private const val LEGACY_FLAGS =
        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY

    fun hideNavigation(activity: Activity) {
        val window = activity.window ?: return
        val decor = window.decorView
        val hide = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.insetsController?.let {
                    it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    it.hide(WindowInsets.Type.navigationBars())
                }
            }
            @Suppress("DEPRECATION")
            run {
                decor.systemUiVisibility = decor.systemUiVisibility or LEGACY_FLAGS
                // The window params survive system-initiated clears better than the view flag alone.
                val lp = window.attributes
                if (lp.systemUiVisibility and LEGACY_FLAGS != LEGACY_FLAGS) {
                    lp.systemUiVisibility = lp.systemUiVisibility or LEGACY_FLAGS
                    window.attributes = lp
                }
                // If the system brings the bar back (after a dialog, say), hide it again.
                decor.setOnSystemUiVisibilityChangeListener { visibility ->
                    if (visibility and View.SYSTEM_UI_FLAG_HIDE_NAVIGATION == 0) {
                        decor.systemUiVisibility = decor.systemUiVisibility or LEGACY_FLAGS
                    }
                }
            }
        }
        hide()
        // Early in a launch the decor isn't attached to the window yet, so apply again once it is.
        decor.post { hide() }
    }

    /** Re-hides the bar whenever [activity]'s window regains focus — the
     *  equivalent of calling hideNavigation() from onWindowFocusChanged(true),
     *  without needing an override in every activity. Safe to call on every
     *  resume: the listener is only added once per window. */
    fun hideOnWindowFocus(activity: Activity) {
        val decor = activity.window?.decorView ?: return
        if (decor.getTag(R.id.system_bars_focus_listener) != null) return
        val listener = ViewTreeObserver.OnWindowFocusChangeListener { hasFocus ->
            if (hasFocus) hideNavigation(activity)
        }
        decor.setTag(R.id.system_bars_focus_listener, listener)
        decor.viewTreeObserver.addOnWindowFocusChangeListener(listener)
    }
}
