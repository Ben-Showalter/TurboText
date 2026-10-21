package com.turbotext.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.util.Log
import java.io.File

private const val TAG = "TurboTextSound"

/**
 * Plays a chosen sound file directly (via MediaPlayer, through the normal
 * speaker) when a message arrives, instead of relying on the system
 * notification's own sound — this is what makes the "No Sound" / pick-a-
 * file / persistent-repeat options actually work, since the system
 * notification sound is just an on/off, single-play thing.
 *
 * Persistent alerts (repeat every X minutes) use AlarmManager rather than
 * a timer that only runs while the app is open, so the reminder keeps
 * firing even if TurboText isn't in the foreground — it stops once the
 * conversation is actually opened (see acknowledge()).
 */
object SoundNotificationHelper {

    private const val PREFS = "message_pro_sound_pending"

    /** Where to look for sound files, per the user's own folder structure:
     *  Internal Storage / notifications / notifications / *.mp3, *.ogg */
    fun soundsDirectory(): File =
        File(Environment.getExternalStorageDirectory(), "notifications")

    /** Recurses into subfolders too — someone organizing their own sound
     *  files into categories (e.g. notifications/Contacts/, notifications/
     *  Fun/) shouldn't have to keep everything flat in the top folder for
     *  it to show up here. */
    fun listAvailableSounds(): List<File> {
        return try {
            val dir = soundsDirectory()
            if (!dir.exists() || !dir.isDirectory) return emptyList()
            dir.walkTopDown()
                .filter { f -> f.isFile && (f.name.endsWith(".mp3", true) || f.name.endsWith(".ogg", true) || f.name.endsWith(".wav", true)) }
                .sortedBy { it.name }
                .toList()
        } catch (e: Exception) {
            Log.w(TAG, "couldn't list sound files", e)
            emptyList()
        }
    }

    /** Call when a new message arrives — plays the chosen sound and/or
     *  vibration pattern once, and if persistent alerts are on, schedules
     *  it to keep repeating until acknowledge() is called for this
     *  address.
     *
     *  [address] must be the same conversation key ConversationActivity
     *  will later call acknowledge() with — NotificationHelper.conversationKey,
     *  i.e. the thread id — or the id used here to post the rich card/alarm
     *  never matches the id used to cancel them and both get stuck.
     *  [displayName], if given, is used as-is instead of re-deriving it
     *  from [address] via ContactHelper — always pass it when [address]
     *  is a thread key, which no contact lookup will ever match.
     *  [sender] is the number it came from, for Do Not Disturb's
     *  contacts-only rule; [preview] is the message text for the cover. */
    fun notifyNewMessage(
        context: Context,
        address: String,
        displayName: String? = null,
        sender: String? = null,
        preview: String? = null
    ) {
        val soundPath = SettingsHelper.getNotificationSoundPath(context)
        val vibratePattern = SettingsHelper.getVibratePattern(context)
        // Do Not Disturb and the ringer switch decide whether this alert
        // may sound or buzz right now (see DndHelper). Repeats are still
        // scheduled, and check again when they fire.
        val level = DndHelper.level(context, sender)

        val name = displayName ?: (ContactHelper.lookupName(context, address) ?: address)
        // The cover screen (closed flip) shows who it's from and what it
        // says, scrolling, until it's read — see CoverScreenNotifier.
        val coverText = when {
            SettingsHelper.isHideMessageOnCover(context) -> "New message"
            preview.isNullOrBlank() -> name
            else -> "$name: ${preview.replace('\n', ' ')}"
        }
        CoverScreenNotifier.notify(context, address.hashCode(), coverText)

        if (soundPath != null && level == DndHelper.Level.FULL) {
            if (File(soundPath).exists()) {
                playSoundWithWakeup(context, soundPath)
            } else {
                Log.w(TAG, "configured sound file no longer exists: $soundPath")
            }
        }
        if (vibratePattern != null && level != DndHelper.Level.SILENT) {
            VibrationPatterns.play(context, vibratePattern)
        }
        // Both "No Sound" and vibrate "Off" — nothing to repeat either.
        if (soundPath == null && vibratePattern == null) return

        val repeatMinutes = SettingsHelper.getRepeatMinutes(context)
        if (repeatMinutes != null) {
            markPending(context, address, true)
            scheduleNextAlarm(context, address, repeatMinutes, sender)
        }
    }

    /** Call when the conversation for this address is actually opened —
     *  stops any persistent repeat alert in progress for it. */
    fun acknowledge(context: Context, address: String) {
        markPending(context, address, false)
        cancelAlarm(context, address)
        CoverScreenNotifier.cancel(context, address.hashCode())
    }

    fun isPending(context: Context, address: String): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(key(address), false)

    private fun markPending(context: Context, address: String, pending: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(key(address), pending)
            .apply()
    }

    private fun key(address: String) = "pending:$address"

    /** Plays a brief silent "wakeup" clip first, then the real notification
     *  sound, instead of going straight to it — a Bluetooth headset/speaker
     *  that's gone to sleep needs the phone to actually start sending it
     *  audio before its own radio/amp finishes waking back up, and by then
     *  a short real notification sound has often already finished playing
     *  on the phone's end and is never actually heard over Bluetooth at
     *  all. The silent lead-in gives that wake-up time to happen before
     *  anything audible starts. Used for real notification playback
     *  (new-message alerts and the persistent repeat); the Settings sound
     *  picker's instant preview-on-tap calls playSound() directly instead,
     *  since a screen you're actively looking at doesn't need this. */
    fun playSoundWithWakeup(context: Context, path: String) {
        try {
            val silence = File(context.cacheDir, "wakeup_silence.wav")
            if (!silence.exists()) DefaultNotificationSounds.writeSilenceWav(silence, 1000)
            val primer = MediaPlayer()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                primer.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
            }
            primer.setDataSource(silence.absolutePath)
            primer.setOnCompletionListener {
                it.release()
                playSound(context, path)
            }
            primer.setOnErrorListener { player, _, _ ->
                player.release()
                playSound(context, path) // primer itself failed — still play the real sound
                true
            }
            primer.prepare()
            primer.start()
        } catch (e: Exception) {
            Log.w(TAG, "wakeup primer failed, playing sound directly", e)
            playSound(context, path)
        }
    }

    fun playSound(context: Context, path: String) {
        try {
            val mp = MediaPlayer()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                mp.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
            }
            mp.setDataSource(path)
            mp.setOnCompletionListener { it.release() }
            mp.setOnErrorListener { player, _, _ -> player.release(); true }
            mp.prepare()
            mp.start()
        } catch (e: Exception) {
            Log.w(TAG, "failed to play notification sound: $path", e)
        }
    }

    private fun alarmPendingIntent(context: Context, address: String, sender: String? = null): PendingIntent {
        val intent = Intent(context, SoundAlarmReceiver::class.java).apply {
            putExtra("address", address)
            putExtra("sender", sender)
        }
        return PendingIntent.getBroadcast(
            context, address.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun scheduleNextAlarm(context: Context, address: String, repeatMinutes: Int, sender: String? = null) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val triggerAt = SystemClock.elapsedRealtime() + repeatMinutes * 60_000L
        try {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, alarmPendingIntent(context, address, sender)
            )
        } catch (e: Exception) {
            Log.w(TAG, "failed to schedule persistent alert", e)
        }
    }

    private fun cancelAlarm(context: Context, address: String) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        try {
            alarmManager.cancel(alarmPendingIntent(context, address))
        } catch (e: Exception) {
            // fine — nothing was scheduled
        }
    }
}

/** Fired by the repeat alarm — re-plays the sound and/or vibration and
 *  reschedules itself, as long as the address hasn't been acknowledged
 *  (conversation opened) since the alarm was set. */
class SoundAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val address = intent.getStringExtra("address") ?: return
        if (!SoundNotificationHelper.isPending(context, address)) return

        val sender = intent.getStringExtra("sender")
        // Checked on every repeat: Do Not Disturb may have come on since.
        val level = DndHelper.level(context, sender)
        val soundPath = SettingsHelper.getNotificationSoundPath(context)
        if (soundPath != null && level == DndHelper.Level.FULL) SoundNotificationHelper.playSoundWithWakeup(context, soundPath)
        val vibratePattern = SettingsHelper.getVibratePattern(context)
        if (vibratePattern != null && level != DndHelper.Level.SILENT) VibrationPatterns.play(context, vibratePattern)

        val repeatMinutes = SettingsHelper.getRepeatMinutes(context) ?: return
        SoundNotificationHelper.scheduleNextAlarm(context, address, repeatMinutes, sender)
    }
}
