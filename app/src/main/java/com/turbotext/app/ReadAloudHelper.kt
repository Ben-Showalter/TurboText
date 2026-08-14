package com.turbotext.app

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager

object ReadAloudHelper {
    fun maybeReadAloud(context: Context, displayName: String, body: String) {
        if (body.isBlank()) return
        val prefixed = "Message from $displayName. $body"
        when (SettingsHelper.getReadAloudMode(context)) {
            "always" -> NativeTtsHelper.speak(context, prefixed)
            "bluetooth" -> if (isBluetoothAudioConnected(context)) NativeTtsHelper.speak(context, prefixed)
            // "never" (or anything else) — do nothing.
        }
    }

    /** Checks for an actively connected Bluetooth *audio* device
     *  specifically (a headset/speaker), not just "Bluetooth is turned
     *  on" — those are different things. */
    private fun isBluetoothAudioConnected(context: Context): Boolean {
        return try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
            val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            devices.any {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            }
        } catch (e: Exception) {
            false
        }
    }
}
