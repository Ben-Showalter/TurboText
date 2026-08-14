package com.turbotext.app

import android.content.Context
import android.os.Handler
import android.os.Looper

/**
 * Keeps a Bluetooth SCO mic route pre-connected while the owning screen is
 * active, so pressing the mic button starts recording immediately instead
 * of paying the ~450ms SCO negotiation cost on every press (see
 * BluetoothMicHelper). Torn back down after [IDLE_TIMEOUT_MS] of no key
 * activity (call [poke] on every key press) or as soon as the screen is
 * left (call [coolDown] from onPause).
 */
class BluetoothMicWarmup(private val context: Context) {
    private var warm = false
    // Bumped by coolDown() so a "connected" broadcast for a request that's
    // since been superseded (e.g. the screen was left mid-negotiation, or
    // idle teardown fired) gets ignored instead of leaving `warm` stuck true
    // when the route has actually already been stopped. Without this, a
    // late connect callback arriving after coolDown() ran would desync this
    // class's idea of "warm" from AudioManager's real state, silently
    // breaking the instant-start benefit for the rest of the session.
    private var generation = 0
    private val handler = Handler(Looper.getMainLooper())
    private val coolDownRunnable = Runnable { coolDown() }

    /** Call when the screen becomes visible, and on every key press while
     *  it's open — (re)starts the SCO route if it isn't already warm, and
     *  pushes the idle-teardown deadline back out. No-ops (and won't start
     *  an idle timer) if the Bluetooth mic feature is off or no eligible
     *  device is connected. */
    fun poke() {
        if (!SettingsHelper.isBluetoothMicEnabled(context) || !BluetoothMicHelper.isBluetoothAudioDeviceConnected(context)) return
        handler.removeCallbacks(coolDownRunnable)
        handler.postDelayed(coolDownRunnable, IDLE_TIMEOUT_MS)
        if (warm) return
        warm = true
        val myGeneration = ++generation
        // silent=true: this is a background pre-connect, not the user
        // actually starting a recording, so it shouldn't buzz the phone the
        // way GroqVoiceInputHelper's own SCO connect does.
        BluetoothMicHelper.startRoutingAndAwaitConnection(context, WARMUP_CONNECT_TIMEOUT_MS, silent = true) { connected ->
            if (myGeneration != generation) return@startRoutingAndAwaitConnection
            if (!connected) warm = false
        }
    }

    /** Call on a key press that's about to make its own Bluetooth-connect
     *  request directly through BluetoothMicHelper (namely the mic button
     *  itself, via GroqVoiceInputHelper.startRecording()). Pushes the
     *  idle-teardown deadline back out like [poke] does, but — unlike
     *  poke() — never issues a competing connect request of its own.
     *
     *  That distinction matters when the route is cold: AudioManager marks
     *  isBluetoothScoOn true the instant a connect is *requested*, not once
     *  it's actually connected, so if poke() and the caller's own request
     *  both fired here, the second one would see "already connected" and
     *  fire its own confirmation vibration immediately — landing right on
     *  top of the recording-started tick as a felt double buzz. */
    fun extendIdle() {
        if (!SettingsHelper.isBluetoothMicEnabled(context) || !BluetoothMicHelper.isBluetoothAudioDeviceConnected(context)) return
        handler.removeCallbacks(coolDownRunnable)
        handler.postDelayed(coolDownRunnable, IDLE_TIMEOUT_MS)
    }

    /** Call when the screen is no longer visible (onPause/onDestroy) —
     *  tears the route back down immediately instead of waiting out the
     *  idle timer. Also invoked by the idle timer itself. */
    fun coolDown() {
        handler.removeCallbacks(coolDownRunnable)
        warm = false
        generation++
        BluetoothMicHelper.stopRouting(context)
    }

    private companion object {
        const val IDLE_TIMEOUT_MS = 15_000L
        // Generous compared to GroqVoiceInputHelper's own per-recording
        // SCO_CONNECT_TIMEOUT_MS — nothing in the UI is blocked on this
        // background pre-connect, so it can afford to wait longer before
        // giving up.
        const val WARMUP_CONNECT_TIMEOUT_MS = 4_000L
    }
}