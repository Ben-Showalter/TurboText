package com.turbotext.app

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Routes GroqVoiceInputHelper's recording through a connected Bluetooth
 * headset's mic instead of the phone's built-in one, when enabled in
 * Advanced settings.
 *
 * Classic Bluetooth only carries a mic signal over HFP/SCO — the same
 * channel phone calls use. A2DP, the profile good headsets use for music
 * playback, is output-only and has no mic uplink. So "using the headset
 * mic" means putting the audio system into SCO/communication mode, same
 * as if a call were active, which is also why AudioSource.VOICE_COMMUNICATION
 * (not MIC) is used for the actual recording — it follows the active
 * communication route as it changes instead of binding to whatever's
 * active the instant recording starts, so a SCO connection that finishes
 * negotiating a moment after startRouting() is called still gets picked
 * up mid-recording.
 */
object BluetoothMicHelper {
    private const val TAG = "TurboTextBtMic"

    private fun hasConnectPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
    }

    /** True if there's a paired, connected Bluetooth device offering a SCO
     *  (HFP) route — the only Bluetooth profile with a mic uplink. A2DP-only
     *  devices (many media earbuds/speakers) intentionally don't count here,
     *  since routing to them would never carry a mic signal. Doesn't
     *  guarantee the SCO connection itself will succeed. */
    fun isBluetoothAudioDeviceConnected(context: Context): Boolean {
        if (!hasConnectPermission(context)) return false
        return try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            }
        } catch (e: Exception) {
            Log.e(TAG, "isBluetoothAudioDeviceConnected check failed", e)
            false
        }
    }

    /** Requests SCO audio routing and waits for it to actually connect
     *  before invoking [callback] with true — or false if it fails/times
     *  out, in which case the caller should fall back to the phone mic.
     *  Needed because the audio HAL on some phones binds the recording
     *  input device at stream-open time rather than following route
     *  changes, so starting to record before SCO finishes negotiating
     *  means it never picks up the Bluetooth mic at all. */
    fun startRoutingAndAwaitConnection(
        context: Context,
        timeoutMs: Long,
        silent: Boolean = false,
        callback: (Boolean) -> Unit
    ) {
        if (!hasConnectPermission(context)) {
            callback(false)
            return
        }
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        if (audioManager == null) {
            callback(false)
            return
        }
        if (audioManager.isBluetoothScoOn) {
            if (!silent) VibrationPatterns.tick(context, 150)
            callback(true)
            return
        }

        val handler = Handler(Looper.getMainLooper())
        var settled = false
        lateinit var receiver: BroadcastReceiver

        fun finish(connected: Boolean) {
            if (settled) return
            settled = true
            handler.removeCallbacksAndMessages(null)
            try {
                context.unregisterReceiver(receiver)
            } catch (e: Exception) {
                // already unregistered — fine
            }
            Log.i(TAG, if (connected) "Bluetooth SCO connected" else "Bluetooth SCO did not connect, falling back to phone mic")
            if (connected && !silent) VibrationPatterns.tick(context, 150)
            callback(connected)
        }

        receiver = object : BroadcastReceiver() {
            // Only SCO_AUDIO_STATE_CONNECTED is trusted as a final answer.
            // DISCONNECTED/ERROR broadcasts also fire as transient
            // "not connected yet" notifications while negotiation is still
            // in progress (confirmed by logcat: one arrives ~20ms after the
            // request, while the real connection lands ~450ms later) — so
            // treating those as failure means bailing out before SCO ever
            // gets a chance to finish. A genuine failure just falls through
            // to the timeout below instead.
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1) == AudioManager.SCO_AUDIO_STATE_CONNECTED) {
                    finish(true)
                }
            }
        }
        context.registerReceiver(receiver, IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED))
        handler.postDelayed({ finish(false) }, timeoutMs)

        try {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            audioManager.startBluetoothSco()
            audioManager.isBluetoothScoOn = true
            Log.i(TAG, "requested Bluetooth SCO routing, awaiting connection")
        } catch (e: Exception) {
            Log.e(TAG, "startRouting failed", e)
            finish(false)
        }
    }

    /** Hands audio routing back to normal. Call once recording (and its
     *  upload) is finished, so the headset doesn't stay stuck in
     *  call-mode until the next SCO-using app touches it. */
    fun stopRouting(context: Context) {
        if (!hasConnectPermission(context)) return
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            if (!audioManager.isBluetoothScoOn) return
            audioManager.isBluetoothScoOn = false
            audioManager.stopBluetoothSco()
            audioManager.mode = AudioManager.MODE_NORMAL
            Log.i(TAG, "stopped Bluetooth SCO routing")
        } catch (e: Exception) {
            Log.e(TAG, "stopRouting failed", e)
        }
    }
}