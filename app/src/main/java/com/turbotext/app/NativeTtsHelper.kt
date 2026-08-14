package com.turbotext.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.telephony.TelephonyManager
import android.util.Log
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/** On-device text-to-speech via Android's built-in TextToSpeech API,
 *  using whatever the phone's system-default engine is — no engine
 *  switching, no network calls.
 *
 *  Read-aloud playback holds real audio focus and a real (if invisible)
 *  MediaSession while it's speaking, rather than just calling
 *  TextToSpeech.speak() in isolation — that's what's needed for two
 *  things: yielding automatically when a phone call takes the audio
 *  channel (a call requests focus the same way any other player would,
 *  so losing focus is the signal to stop), and responding to a hardware
 *  pause press from a Bluetooth audio device, which Android routes to
 *  whichever MediaSession is currently active rather than to any
 *  particular app directly. */
object NativeTtsHelper {
    private const val TAG = "TurboTextNativeTts"
    private var tts: TextToSpeech? = null
    @Volatile private var ready = false
    @Volatile private var failed = false
    private val pendingQueue = ConcurrentLinkedQueue<String>()
    private val mainHandler = Handler(Looper.getMainLooper())

    // Tracks how many utterances are still in flight (TTS's own queue can
    // hold more than one at once via QUEUE_ADD) — focus/session are only
    // released once this drops back to zero, not after every individual
    // utterance.
    private val pendingUtterances = AtomicInteger(0)

    private var audioManager: AudioManager? = null
    private var telephonyManager: TelephonyManager? = null
    private var mediaSession: MediaSession? = null
    private var focusRequest: AudioFocusRequest? = null
    private val legacyFocusListener = AudioManager.OnAudioFocusChangeListener { handleFocusChange(it) }

    private fun ensureInitialized(context: Context) {
        if (tts != null) return
        val appContext = context.applicationContext
        audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        telephonyManager = appContext.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        tts = TextToSpeech(appContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
                applyPreferred(context)
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) = onUtteranceFinished()
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) = onUtteranceFinished()
                    override fun onStop(utteranceId: String?, interrupted: Boolean) = onUtteranceFinished()
                })
                ready = true
                Log.i(TAG, "TTS engine initialized successfully")
                var next = pendingQueue.poll()
                while (next != null) {
                    speakNow(appContext, next)
                    next = pendingQueue.poll()
                }
            } else {
                failed = true
                Log.w(TAG, "TTS engine failed to initialize (status=$status)")
            }
        }
    }

    /** Re-applies the saved voice/rate preference — needed every time
     *  the engine initializes fresh (app restart, etc.), since a new
     *  TextToSpeech instance always starts back at its own defaults. */
    private fun applyPreferred(context: Context) {
        val savedVoiceName = SettingsHelper.getNativeVoiceName(context)
        if (savedVoiceName != null) {
            tts?.voices?.find { it.name == savedVoiceName }?.let { tts?.voice = it }
        }
        tts?.setSpeechRate(SettingsHelper.getNativeSpeechRate(context))
    }

    fun speak(context: Context, text: String) {
        ensureInitialized(context)
        if (isOnCall()) {
            Log.i(TAG, "on a call — skipping read-aloud")
            return
        }
        when {
            ready -> speakNow(context.applicationContext, text)
            failed -> Log.w(TAG, "TTS unavailable — dropping speak request")
            else -> pendingQueue.add(text) // still initializing — flushed once ready
        }
    }

    private fun speakNow(context: Context, text: String) {
        mainHandler.post {
            pendingUtterances.incrementAndGet()
            acquireFocusAndSession(context)
            tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "ft_${System.nanoTime()}")
        }
    }

    private fun onUtteranceFinished() {
        if (pendingUtterances.decrementAndGet() <= 0) {
            pendingUtterances.set(0)
            mainHandler.post { releaseFocusAndSession() }
        }
    }

    private fun isOnCall(): Boolean {
        return try {
            @Suppress("DEPRECATION")
            telephonyManager?.callState != TelephonyManager.CALL_STATE_IDLE
        } catch (e: Exception) {
            false
        }
    }

    /** A real (if silent — this is speech, not music) MediaSession and
     *  audio focus request, held only while something is actually
     *  speaking. Needed for both halves of "pause the readout": losing
     *  focus (AUDIOFOCUS_LOSS/_TRANSIENT) is how a phone call ringing or
     *  going off-hook shows up here, and the session's onPause()/onStop()
     *  callbacks are where a hardware pause press — including one from a
     *  Bluetooth headset/speaker's own button — actually lands, since
     *  Android routes media button events to the current active session
     *  rather than to a specific app. */
    private fun acquireFocusAndSession(context: Context) {
        if (mediaSession == null) {
            mediaSession = MediaSession(context, "TurboTextReadAloud").apply {
                setCallback(object : MediaSession.Callback() {
                    override fun onPause() { stop() }
                    override fun onStop() { stop() }
                })
                setPlaybackState(
                    PlaybackState.Builder()
                        .setActions(PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_STOP)
                        .setState(PlaybackState.STATE_PLAYING, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
                        .build()
                )
                isActive = true
            }
        }
        val am = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (focusRequest == null) {
                focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setOnAudioFocusChangeListener { handleFocusChange(it) }
                    .build()
            }
            am.requestAudioFocus(focusRequest!!)
        } else {
            @Suppress("DEPRECATION")
            am.requestAudioFocus(legacyFocusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        }
    }

    private fun releaseFocusAndSession() {
        mediaSession?.isActive = false
        mediaSession?.release()
        mediaSession = null
        val am = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { am.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            am.abandonAudioFocus(legacyFocusListener)
        }
    }

    /** A call ringing or going off-hook requests focus the same way any
     *  other player would, so a plain focus loss covers "pause on a
     *  phone call" — no separate call-state listener needed on top of
     *  the up-front isOnCall() check in speak(). */
    private fun handleFocusChange(focusChange: Int) {
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> stop()
        }
    }

    fun stop() {
        tts?.stop()
        pendingQueue.clear()
        pendingUtterances.set(0)
        mainHandler.post { releaseFocusAndSession() }
    }

    /** Reports success/failure a moment later — for the Settings
     *  preview, since knowing whether this actually works on this
     *  device at all is the entire point of offering one. */
    fun previewWithStatus(context: Context, text: String, onResult: (Boolean, String) -> Unit) {
        speak(context, text)
        Handler(Looper.getMainLooper()).postDelayed({
            when {
                ready -> onResult(true, "Playing — if you didn't hear anything, check the phone's volume")
                failed -> onResult(false, "No text-to-speech engine is available on this device")
                else -> onResult(false, "Still trying to start the TTS engine — it may not be available")
            }
        }, 800)
    }

    /** Every voice the system engine reports, best-quality first
     *  (Android's Voice.quality: higher is better), English-tagged ones
     *  preferred among those. */
    fun listVoicesWithStatus(context: Context, onResult: (List<android.speech.tts.Voice>) -> Unit) {
        ensureInitialized(context)
        fun deliver() {
            val all = tts?.voices?.toList() ?: emptyList()
            val sorted = all
                .filter { !it.isNetworkConnectionRequired }
                .sortedWith(compareByDescending<android.speech.tts.Voice> { it.locale.language == "en" }
                    .thenByDescending { it.quality })
            onResult(sorted)
        }
        if (ready || failed) deliver() else Handler(Looper.getMainLooper()).postDelayed({ deliver() }, 800)
    }

    fun setVoice(context: Context, voiceName: String) {
        ensureInitialized(context)
        SettingsHelper.setNativeVoiceName(context, voiceName)
        tts?.voices?.find { it.name == voiceName }?.let { tts?.voice = it }
    }

    fun setSpeechRate(context: Context, rate: Float) {
        ensureInitialized(context)
        SettingsHelper.setNativeSpeechRate(context, rate)
        tts?.setSpeechRate(rate)
    }
}
