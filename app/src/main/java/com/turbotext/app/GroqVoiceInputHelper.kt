package com.turbotext.app

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

private const val TAG = "TurboTextVoice"
// How long to give Bluetooth SCO to connect before falling back to the
// phone mic — generous enough for real-world negotiation time without
// making a push-to-talk tap feel broken if the headset never answers.
private const val SCO_CONNECT_TIMEOUT_MS = 2000L

/**
 * Push-to-talk voice input: hold a key to record, release to send the clip
 * to Groq's (free-tier) hosted Whisper API and get text back. Whisper
 * outputs proper punctuation and capitalization on its own, so no extra
 * post-processing is needed for that.
 *
 * This replaced an earlier on-device Vosk approach — this phone's CPU
 * struggled with even Vosk's small model, and Whisper is heavier still, so
 * offloading transcription to Groq's servers sidesteps that entirely.
 */
class GroqVoiceInputHelper(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var usingBluetoothMic = false
    // True between startRecording() kicking off a Bluetooth-mic session and
    // beginRecording() actually running — SCO negotiation means that gap
    // isn't instant, so a very quick tap can release before it closes.
    // stopRecordingAndTranscribe() defers into pendingStop until it does.
    private var awaitingRoute = false
    // Set by cancelRecording() if it's called while still awaiting SCO, so
    // beginRecording() knows to bail out instead of starting a recording
    // that cancelRecording() already told the caller was discarded.
    private var cancelled = false
    private var pendingStop: Pair<(String) -> Unit, (String) -> Unit>? = null
    // Set by callers (ConversationActivity's mic warmup) that pre-connect the
    // Bluetooth route before recording starts and keep it alive between
    // presses themselves — when true, a finished/cancelled recording leaves
    // SCO routing as-is instead of tearing it down, since the caller still
    // owns it and will close it on its own idle timeout / lifecycle.
    var keepBluetoothRouteWarm = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** Call on key-down. Returns false (and shows nothing) if recording
     *  couldn't be kicked off, so the caller can decide how to handle that.
     *  When routing through a Bluetooth mic, the actual MediaRecorder start
     *  is deferred until SCO connects (or times out and falls back to the
     *  phone mic) — see BluetoothMicHelper.startRoutingAndAwaitConnection —
     *  so a true return there means "attempt under way", not "recording now". */
    fun startRecording(): Boolean {
        pendingStop = null
        return try {
            val file = File(context.cacheDir, "voice_${System.currentTimeMillis()}.m4a")
            outputFile = file
            usingBluetoothMic = SettingsHelper.isBluetoothMicEnabled(context) &&
                BluetoothMicHelper.isBluetoothAudioDeviceConnected(context)
            if (usingBluetoothMic) {
                awaitingRoute = true
                BluetoothMicHelper.startRoutingAndAwaitConnection(context, SCO_CONNECT_TIMEOUT_MS) { connected ->
                    usingBluetoothMic = connected
                    awaitingRoute = false
                    beginRecording(file, useBluetoothSource = connected)
                }
                true
            } else {
                awaitingRoute = false
                beginRecording(file, useBluetoothSource = false)
            }
        } catch (e: Exception) {
            Log.e(TAG, "startRecording failed", e)
            recorder = null
            awaitingRoute = false
            releaseBluetoothRoutingIfNeeded()
            false
        }
    }

    /** Actually starts the MediaRecorder — either called synchronously from
     *  startRecording() (phone mic) or once SCO has resolved (Bluetooth
     *  mic). Returns whether it succeeded, and resolves any stop request
     *  that arrived while a Bluetooth mic session was still awaiting SCO. */
    private fun beginRecording(file: File, useBluetoothSource: Boolean): Boolean {
        if (cancelled) {
            cancelled = false
            releaseBluetoothRoutingIfNeeded()
            return false
        }
        val started = try {
            val mr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            mr.setAudioSource(
                if (useBluetoothSource) MediaRecorder.AudioSource.VOICE_COMMUNICATION
                else MediaRecorder.AudioSource.MIC
            )
            mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            mr.setAudioSamplingRate(16000)
            mr.setAudioEncodingBitRate(32000)
            mr.setOutputFile(file.absolutePath)
            mr.prepare()
            mr.start()
            recorder = mr
            Log.i(TAG, "recording started -> ${file.name} (bluetoothMic=$useBluetoothSource)")
            VibrationPatterns.tick(context, 40)
            true
        } catch (e: Exception) {
            Log.e(TAG, "startRecording failed", e)
            recorder = null
            releaseBluetoothRoutingIfNeeded()
            false
        }

        val deferredStop = pendingStop
        if (deferredStop != null) {
            pendingStop = null
            stopRecordingAndTranscribe(deferredStop.first, deferredStop.second)
        }
        return started
    }

    /** Hands Bluetooth SCO routing back to normal once a recording session
     *  is done — a no-op if this session used the phone mic. */
    private fun releaseBluetoothRoutingIfNeeded() {
        if (!usingBluetoothMic) return
        usingBluetoothMic = false
        if (keepBluetoothRouteWarm) return
        BluetoothMicHelper.stopRouting(context)
    }

    /** Call on key-up. Stops recording and uploads the clip in the
     *  background; onResult/onError are invoked on the main thread. If a
     *  Bluetooth-mic session is still waiting on SCO to connect, the stop
     *  is deferred until beginRecording() actually runs. */
    fun stopRecordingAndTranscribe(onResult: (String) -> Unit, onError: (String) -> Unit) {
        if (awaitingRoute) {
            pendingStop = onResult to onError
            return
        }
        val mr = recorder
        val file = outputFile
        recorder = null
        if (mr == null || file == null) {
            onError("Recording didn't start")
            return
        }
        try {
            mr.stop()
        } catch (e: Exception) {
            // Throws if almost no audio was captured (very quick tap) —
            // treat that as "nothing said" rather than a real error.
            Log.w(TAG, "stop() threw (likely too-short recording)", e)
            mr.release()
            releaseBluetoothRoutingIfNeeded()
            file.delete()
            onError("Recording too short")
            return
        }
        mr.release()
        releaseBluetoothRoutingIfNeeded()
        Log.i(TAG, "recording stopped, uploading ${file.length()} bytes")

        if (GroqConfig.getApiKey(context).isBlank() || GroqConfig.getApiKey(context) == "PUT_YOUR_GROQ_API_KEY_HERE") {
            onError("No Groq API key set yet — see README")
            file.delete()
            return
        }

        Thread {
            // Try trimming silence off both ends before upload — smaller
            // file, faster round-trip. Falls back to the original
            // recording if trimming doesn't work out for any reason;
            // this should never be the thing that breaks voice-to-text.
            val trimmedFile = File(context.cacheDir, "voice_trimmed_${System.currentTimeMillis()}.m4a")
            val uploadFile = if (AudioTrimmer.trimSilence(file, trimmedFile)) {
                Log.i(TAG, "trimmed ${file.length()} bytes -> ${trimmedFile.length()} bytes")
                trimmedFile
            } else {
                trimmedFile.delete()
                file
            }

            try {
                val requestBody = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("model", "whisper-large-v3-turbo")
                    .addFormDataPart(
                        "file", uploadFile.name,
                        uploadFile.asRequestBody("audio/m4a".toMediaTypeOrNull())
                    )
                    .build()

                val request = Request.Builder()
                    .url("https://api.groq.com/openai/v1/audio/transcriptions")
                    .addHeader("Authorization", "Bearer ${GroqConfig.getApiKey(context)}")
                    .post(requestBody)
                    .build()

                client.newCall(request).execute().use { response ->
                    val bodyStr = response.body?.string() ?: ""
                    if (!response.isSuccessful) {
                        Log.e(TAG, "Groq error ${response.code}: $bodyStr")
                        mainHandler.post { onError("Groq error ${response.code} — check your API key / internet") }
                        return@Thread
                    }
                    val text = JSONObject(bodyStr).optString("text").trim()
                    Log.i(TAG, "Groq transcription: \"$text\"")
                    if (text.isNotEmpty()) VibrationPatterns.tick(context, 40)
                    mainHandler.post { onResult(text) }
                }
            } catch (e: IOException) {
                Log.e(TAG, "network error", e)
                mainHandler.post { onError("No internet connection reached Groq") }
            } catch (e: Exception) {
                Log.e(TAG, "transcription error", e)
                mainHandler.post { onError("Voice error: ${e.message}") }
            } finally {
                file.delete()
                trimmedFile.delete()
            }
        }.start()
    }

    /** Discards an in-progress recording without sending it anywhere. If a
     *  Bluetooth-mic session is still awaiting SCO, marks it so
     *  beginRecording() aborts instead of starting a recording nothing will
     *  ever stop. */
    fun cancelRecording() {
        if (awaitingRoute) {
            cancelled = true
            awaitingRoute = false
            pendingStop = null
            outputFile?.delete()
            outputFile = null
            return
        }
        try {
            recorder?.stop()
        } catch (e: Exception) {
            // fine — we're discarding it anyway
        }
        recorder?.release()
        recorder = null
        releaseBluetoothRoutingIfNeeded()
        outputFile?.delete()
        outputFile = null
    }
}
