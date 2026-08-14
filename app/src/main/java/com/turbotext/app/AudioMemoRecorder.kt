package com.turbotext.app

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import java.io.File

/** Records a voice memo for MMS attachment — separate from
 *  GroqVoiceInputHelper, which is tuned specifically for fast, cheap
 *  voice-to-text transcription (lower sample rate/bitrate, since Whisper
 *  doesn't need high fidelity). This one is meant to actually be
 *  listened to by whoever receives it, so it uses real audio quality
 *  settings instead. */
class AudioMemoRecorder(private val context: Context) {
    companion object {
        private const val TAG = "TurboTextAudioMemo"
    }

    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null

    fun startRecording(): Boolean {
        return try {
            val dir = File(context.cacheDir, "audio_memos")
            dir.mkdirs()
            val file = File(dir, "memo_${System.currentTimeMillis()}.m4a")
            outputFile = file
            val mr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            mr.setAudioSource(MediaRecorder.AudioSource.MIC)
            mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            mr.setAudioSamplingRate(44100)
            mr.setAudioEncodingBitRate(64000)
            mr.setOutputFile(file.absolutePath)
            mr.prepare()
            mr.start()
            recorder = mr
            Log.i(TAG, "recording started -> ${file.name}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "startRecording failed", e)
            recorder = null
            false
        }
    }

    /** Stops recording and returns a shareable content:// URI for the
     *  recorded file, or null if nothing was recorded / it failed. */
    fun stopRecording(): android.net.Uri? {
        val mr = recorder ?: return null
        return try {
            mr.stop()
            mr.release()
            recorder = null
            val file = outputFile ?: return null
            androidx.core.content.FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", file
            )
        } catch (e: Exception) {
            Log.e(TAG, "stopRecording failed", e)
            try { recorder?.release() } catch (e2: Exception) { }
            recorder = null
            null
        }
    }

    fun cancelRecording() {
        try { recorder?.stop() } catch (e: Exception) { }
        try { recorder?.release() } catch (e: Exception) { }
        recorder = null
        outputFile?.delete()
        outputFile = null
    }
}
