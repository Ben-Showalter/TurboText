package com.turbotext.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

private const val TAG = "TurboTextTranslate"

/** Translates message text to English via Groq's chat completions API —
 *  the same free-tier account already used for voice-to-text (see
 *  GroqVoiceInputHelper), just a different endpoint: plain text chat
 *  instead of audio transcription. */
object GroqTranslateHelper {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun translateToEnglish(context: Context, text: String, onResult: (String) -> Unit, onError: (String) -> Unit) {
        Thread {
            try {
                val messages = JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "system")
                        put(
                            "content",
                            "Translate the user's message to English. Reply with ONLY the " +
                                "translation, nothing else — no notes, no quotation marks, no " +
                                "explanation. If the message is already in English, reply with " +
                                "it unchanged."
                        )
                    })
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", text)
                    })
                }
                val requestBody = JSONObject().apply {
                    put("model", "openai/gpt-oss-20b")
                    put("messages", messages)
                    put("temperature", 0.2)
                }.toString().toRequestBody("application/json".toMediaTypeOrNull())

                val request = Request.Builder()
                    .url("https://api.groq.com/openai/v1/chat/completions")
                    .addHeader("Authorization", "Bearer ${GroqConfig.getApiKey(context)}")
                    .post(requestBody)
                    .build()

                client.newCall(request).execute().use { response ->
                    val bodyStr = response.body?.string() ?: ""
                    if (!response.isSuccessful) {
                        Log.e(TAG, "Groq error ${response.code}: $bodyStr")
                        mainHandler.post { onError("Translation error ${response.code} — check your API key / internet") }
                        return@Thread
                    }
                    val json = JSONObject(bodyStr)
                    val translated = json.getJSONArray("choices")
                        .getJSONObject(0)
                        .getJSONObject("message")
                        .getString("content")
                        .trim()
                    Log.i(TAG, "translated: \"$translated\"")
                    mainHandler.post { onResult(translated) }
                }
            } catch (e: IOException) {
                Log.e(TAG, "network error", e)
                mainHandler.post { onError("No internet connection reached Groq") }
            } catch (e: Exception) {
                Log.e(TAG, "translate error", e)
                mainHandler.post { onError("Translation error: ${e.message}") }
            }
        }.start()
    }
}
