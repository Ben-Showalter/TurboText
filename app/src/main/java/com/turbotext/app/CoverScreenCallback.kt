package com.turbotext.app

import android.content.Context
import android.util.Log
import android.view.KeyEvent
import jp.kyocera.sublcd.ISubLcdCallback

/**
 * Listens to Kyocera's cover-screen service (`SubLcdManager.registerCallback`,
 * MatChat's docs/adr/0009) for the one event Android doesn't give apps: the cover screen
 * turning **on** — when the flip closes, and when an outside button wakes it
 * with the flip shut. Confirmed on the DuraXV by MatChat's debug probe
 * (`onScreenStateChanged` 1 = on, 0 = off; cover keys are not forwarded).
 *
 * Handles nothing: every key callback answers "not handled" (false), so no
 * button changes behavior. Compiled against `:stubs:kyocera-sublcd`
 * (compileOnly); at runtime the phone's own class is used. Off Kyocera that
 * class doesn't exist, so [register] fails inside its guard and does nothing.
 */
internal object CoverScreenCallback {

    private val TAG = CoverScreenNotifier.TAG

    /** `SubLcdManager.STATE_SCREEN_ON`, read by the probe. */
    private const val STATE_SCREEN_ON = 1

    /** The registered listener, held as [Any] so nothing outside the guarded
     *  calls below touches a class that only exists on Kyocera phones. */
    private var registered: Any? = null

    /** Starts listening; [onCoverOn] runs on a binder thread each time the
     *  cover turns on. No-op if already listening or not on Kyocera. */
    fun register(context: Context, onCoverOn: () -> Unit) {
        if (registered != null) return
        runCatching {
            val listener = Listener(onCoverOn)
            call(context, "registerCallback", listener)
            listener
        }.onSuccess {
            registered = it
            Log.d(TAG, "cover callback registered")
        }.onFailure {
            Log.d(TAG, "cover callback unavailable: ${it.javaClass.simpleName}")
        }
    }

    fun unregister(context: Context) {
        val listener = registered ?: return
        registered = null
        runCatching { call(context, "unregisterCallback", listener) }
            .onSuccess { Log.d(TAG, "cover callback unregistered") }
            .onFailure { Log.d(TAG, "cover callback unregister failed: ${it.javaClass.simpleName}") }
    }

    private fun call(context: Context, method: String, listener: Any) {
        val managerClass = Class.forName("jp.kyocera.sublcd.SubLcdManager")
        val manager = managerClass.getMethod("getInstance", Context::class.java).invoke(null, context)
        managerClass.getMethod(method, Class.forName("jp.kyocera.sublcd.ISubLcdCallback"))
            .invoke(manager, listener)
    }

    /** Runs on binder threads; [onCoverOn] hops to the main thread itself. */
    private class Listener(private val onCoverOn: () -> Unit) : ISubLcdCallback.Stub() {
        override fun onScreenStateChanged(state: Int) {
            if (state == STATE_SCREEN_ON) onCoverOn()
        }

        override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean = false

        override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean = false

        override fun onKeyLongPress(keyCode: Int, event: KeyEvent?): Boolean = false

        override fun onKeyMultiple(keyCode: Int, count: Int, event: KeyEvent?): Boolean = false

        override fun onNotificationCancel(tag: String?, id: Int) = Unit
    }
}
