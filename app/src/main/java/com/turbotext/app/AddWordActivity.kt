package com.turbotext.app

import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class AddWordActivity : AppCompatActivity() {

    private lateinit var wordText: EditText
    private lateinit var modeIndicator: TextView
    private lateinit var listeningIndicator: TextView
    private lateinit var inputController: T9InputController
    private lateinit var voiceHelper: GroqVoiceInputHelper
    private lateinit var engine: T9Engine

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_add_word)

        ThemeHelper.apply(this)
        wordText = findViewById(R.id.wordText)
        wordText.setShowSoftInputOnFocus(false)
        modeIndicator = findViewById(R.id.modeIndicator)
        listeningIndicator = findViewById(R.id.listeningIndicator)

        engine = T9EngineHolder.get(this)
        inputController = T9InputController(
            engine = engine,
            outputView = wordText,
            onModeChanged = { mode -> modeIndicator.text = mode.label },
            onSuggestionsChanged = { candidates, selected, windowSize -> renderSuggestions(candidates, selected, windowSize) }
        )
        inputController.startCursorBlink()
        voiceHelper = GroqVoiceInputHelper(this)
        wordText.requestFocus()
    }

    private fun renderSuggestions(candidates: List<String>, selected: Int, windowSize: Int = 5) {
        val suggestionsBar = findViewById<TextView>(R.id.suggestionsBar)
        if (candidates.isEmpty()) {
            suggestionsBar.visibility = View.GONE
            return
        }
        suggestionsBar.visibility = View.VISIBLE
        suggestionsBar.text = SuggestionRenderer.build(candidates, selected, this, windowSize)
    }

    private fun saveAndClose() {
        val word = inputController.currentText().trim()
        if (word.isEmpty()) {
            finish()
            return
        }
        if (engine.addUserWord(word)) {
            Toast.makeText(this, "\"$word\" added to dictionary", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Couldn't add that word", Toast.LENGTH_SHORT).show()
            return
        }
        finish()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_UP) {
            if (event.keyCode in MicButtonKeyCodes.CODES) {
                stopVoiceRecordingAndSend()
                return true
            }
            if (inputController.onKeyUp(event.keyCode)) return true
            return super.dispatchKeyEvent(event)
        }

        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)

        if (event.keyCode == KeyEvent.KEYCODE_SOFT_RIGHT) {
            saveAndClose()
            return true
        }
        if (event.keyCode in MicButtonKeyCodes.CODES) {
            if (event.repeatCount == 0) startVoiceRecording()
            return true
        }
        if (event.keyCode == KeyEvent.KEYCODE_DEL || event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (inputController.hasContent()) {
                inputController.onKeyDown(KeyEvent.KEYCODE_DEL, event)
            } else {
                saveAndClose()
            }
            return true
        }

        if (inputController.onKeyDown(event.keyCode, event)) return true
        return true
    }

    private fun startVoiceRecording() {
        if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            androidx.core.app.ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.RECORD_AUDIO), 200)
            return
        }
        if (voiceHelper.startRecording()) {
            listeningIndicator.text = "Recording…"
            listeningIndicator.visibility = View.VISIBLE
        }
    }

    private fun stopVoiceRecordingAndSend() {
        listeningIndicator.text = "Transcribing…"
        voiceHelper.stopRecordingAndTranscribe(
            onResult = { text ->
                listeningIndicator.visibility = View.GONE
                if (text.isNotEmpty()) inputController.appendVoiceResult(text)
            },
            onError = { message ->
                listeningIndicator.visibility = View.GONE
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            }
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        voiceHelper.cancelRecording()
        inputController.stopCursorBlink()
    }
}
