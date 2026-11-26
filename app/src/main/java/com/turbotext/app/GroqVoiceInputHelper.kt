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
    private val mainHandler = Handler(Looper.getMainLooper())
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** Bumped by cancelRecording(), so an upload already under way is
     *  dropped and its text never lands after you've moved on (TurboType). */
    @Volatile private var generation = 0

    val isRecording: Boolean get() = recorder != null

    /** Call on key-down. Returns false if recording couldn't start, so
     *  the caller can decide how to handle that. Always the phone's mic. */
    fun startRecording(): Boolean {
        val file = File(context.cacheDir, "voice_${System.currentTimeMillis()}.m4a")
        outputFile = file
        return try {
            val mr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            mr.setAudioSource(MediaRecorder.AudioSource.MIC)
            mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            mr.setAudioSamplingRate(16000)
            mr.setAudioEncodingBitRate(32000)
            mr.setOutputFile(file.absolutePath)
            mr.prepare()
            mr.start()
            recorder = mr
            Log.i(TAG, "recording started -> ${file.name}")
            VibrationPatterns.tick(context, 40)
            true
        } catch (e: Exception) {
            Log.e(TAG, "startRecording failed", e)
            recorder = null
            file.delete()
            false
        }
    }

    /** Call on key-up. Stops recording and uploads the clip in the
     *  background; onResult/onError are invoked on the main thread. */
    fun stopRecordingAndTranscribe(onResult: (String) -> Unit, onError: (String) -> Unit) {
        val mr = recorder
        val file = outputFile
        recorder = null
        outputFile = null
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
            file.delete()
            onError("Recording too short")
            return
        }
        mr.release()
        Log.i(TAG, "recording stopped, uploading ${file.length()} bytes")

        // Read once per upload, never logged.
        val apiKey = GroqConfig.getApiKey(context)
        if (apiKey.isBlank() || apiKey == "PUT_YOUR_GROQ_API_KEY_HERE") {
            onError("No API key")
            file.delete()
            return
        }
        val myGeneration = generation
        fun deliver(block: () -> Unit) {
            mainHandler.post { if (myGeneration == generation) block() }
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
                    .addHeader("Authorization", "Bearer $apiKey")
                    .post(requestBody)
                    .build()

                client.newCall(request).execute().use { response ->
                    val bodyStr = response.body?.string() ?: ""
                    if (!response.isSuccessful) {
                        Log.e(TAG, "Groq error ${response.code}")
                        // Short plain words for the status line (TurboType).
                        val message = when (response.code) {
                            401, 403 -> "Voice key not accepted"
                            429 -> "Voice limit reached, try later"
                            else -> "Voice service error ${response.code}"
                        }
                        deliver { onError(message) }
                        return@Thread
                    }
                    val text = JSONObject(bodyStr).optString("text").trim()
                    Log.i(TAG, "Groq transcription: ${text.length} chars")
                    if (text.isNotEmpty() && myGeneration == generation) VibrationPatterns.tick(context, 40)
                    deliver { onResult(text) }
                }
            } catch (e: IOException) {
                Log.e(TAG, "network error", e)
                deliver { onError("No internet") }
            } catch (e: Exception) {
                Log.e(TAG, "transcription error", e)
                deliver { onError("Voice error") }
            } finally {
                file.delete()
                trimmedFile.delete()
            }
        }.start()
    }

    /** Discards an in-progress recording without sending it anywhere. */
    fun cancelRecording() {
        generation++
        try {
            recorder?.stop()
        } catch (e: Exception) {
            // fine — we're discarding it anyway
        }
        recorder?.release()
        recorder = null
        outputFile?.delete()
        outputFile = null
    }
}
