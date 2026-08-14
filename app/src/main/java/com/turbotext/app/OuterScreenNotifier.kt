package com.turbotext.app

import android.app.Notification
import android.content.Context
import android.util.Log

/** Reconstructed by disassembling both the stock Messaging app's
 *  SubLcdUtils.postSubLcd() AND SystemUI's own SubLcd status calls
 *  (classes.dex from com.android.systemui). Built on Android's own
 *  *public* Notification.Builder.extend() API — the same pattern used
 *  for wearable notifications — not a private system broadcast.
 *
 *  A different layout constant (0x010900b1, from SystemUI's own USB-
 *  connected status call, which sets no duration at all) was tried
 *  here on the theory that a genuinely different template might not
 *  need a duration the way the message rich-card does. A real device
 *  test showed it holds the screen on continuously, exactly like the
 *  original layout does without a duration — so this is now proven,
 *  not just suspected, across two different layouts and several
 *  priority/category combinations: every notify() call needs *some*
 *  duration to let the screen return to idle, and any duration means
 *  the content clears when it expires. There's no configuration of
 *  this API that persists indefinitely without holding the screen.
 *  Back to the original, known-working duration=5000 rich card.
 *
 *  pulse()/pulseFlashing() remain the other approach: showing the icon
 *  right when a hardware key press wakes the screen (PTT/SOS reliably,
 *  while the phone is closed), rather than trying to make one post
 *  persist unattended. */
object OuterScreenNotifier {
    private const val TAG = "TurboTextOuterScreen"
    private val flashHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** Sentinel id for pulse() — not tied to any specific conversation,
     *  since this is a generic "briefly show the icon" flash rather than
     *  a per-message alert with its own read/cancel lifecycle. */
    private const val PULSE_ID = -1

    fun notify(context: Context, id: Int, text: String) {
        postToSubLcd(context, id, text, layout = 0x010900af, priority = 0, durationMs = 5000)
    }

    /** On for 1s, off for 1s, repeated 4 times (~8s total) — the clock
     *  is visible in the gaps rather than the icon holding the screen
     *  the whole time, but a key press only needs to land within one of
     *  four 1-second windows instead of a single one-shot flash. */
    fun pulseFlashing(context: Context) {
        for (i in 0 until 4) {
            flashHandler.postDelayed({ pulse(context) }, i * 2000L)
        }
    }

    /** Brief, icon-only, no-text flash — meant to be called right as a
     *  hardware key press wakes the outer screen, so there's something
     *  to see at that moment without needing to hold the screen awake
     *  or repost on a timer. Still uses the original rich-card layout,
     *  since it relies on setDuration() to auto-clear itself — this one
     *  is deliberately brief and self-expiring, unlike notify() above. */
    fun pulse(context: Context) {
        postToSubLcd(context, PULSE_ID, text = null, layout = 0x010900af, priority = 0, durationMs = 1000)
    }

    private fun postToSubLcd(
        context: Context, id: Int, text: String?, layout: Int, priority: Int, durationMs: Int?
    ) {
        try {
            val managerClass = Class.forName("jp.kyocera.sublcd.SubLcdManager")
            val extenderClass = Class.forName("jp.kyocera.sublcd.SubLcdNotificationExtender")

            val manager = managerClass.getMethod("getInstance", Context::class.java)
                .invoke(null, context)

            val extender = extenderClass.getConstructor(Context::class.java).newInstance(context)
            extenderClass.getMethod("setCategory", Int::class.javaPrimitiveType)
                .invoke(extender, 2)
            extenderClass.getMethod("setLayout", Int::class.javaPrimitiveType)
                .invoke(extender, layout)
            extenderClass.getMethod("setPriority", Int::class.javaPrimitiveType)
                .invoke(extender, priority)
            extenderClass.getMethod("setIcon", Int::class.javaPrimitiveType)
                .invoke(extender, R.drawable.ic_sublcd_message)
            if (text != null) {
                extenderClass.getMethod("setText", CharSequence::class.java)
                    .invoke(extender, text)
            }
            if (durationMs != null) {
                extenderClass.getMethod("setDuration", Int::class.javaPrimitiveType)
                    .invoke(extender, durationMs)
            }

            val builder = Notification.Builder(context).setContentTitle("TurboText")
            if (text != null) builder.setContentText(text)
            Notification.Builder::class.java
                .getMethod("extend", Notification.Extender::class.java)
                .invoke(builder, extender)
            val notification = builder.build()

            managerClass.getMethod("notify", Int::class.javaPrimitiveType, Notification::class.java)
                .invoke(manager, id, notification)
            Log.i(TAG, "outer-screen notify completed (id=$id, layout=${layout.toString(16)}, hasText=${text != null}, duration=$durationMs)")
        } catch (e: Throwable) {
            // Expected outcome if these classes aren't accessible outside
            // the system image, or if the API differs from what the
            // disassembly showed — either way, safe to just do nothing.
            Log.w(TAG, "outer-screen notify not available/failed", e)
        }
    }

    /** Call once the message has actually been read — clears the
     *  outer-screen symbol left by notify() above, using the same id. */
    fun cancel(context: Context, id: Int) {
        try {
            val managerClass = Class.forName("jp.kyocera.sublcd.SubLcdManager")
            val manager = managerClass.getMethod("getInstance", Context::class.java)
                .invoke(null, context)
            managerClass.getMethod("cancel", Int::class.javaPrimitiveType)
                .invoke(manager, id)
            Log.i(TAG, "outer-screen cancel call completed without error")
        } catch (e: Throwable) {
            Log.w(TAG, "outer-screen cancel not available/failed", e)
        }
    }
}
