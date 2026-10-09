package com.turbotext.app

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class TextEntryActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_TITLE = "title"
        const val EXTRA_INITIAL_TEXT = "initial_text"
        const val EXTRA_RESULT_TEXT = "result_text"
        const val EXTRA_ALLOW_VOICE = "allow_voice"
        /** When true: right softkey shows an Options menu (Paste) instead
         *  of directly saving, and the physical Send/Call key is what
         *  actually sends — matching how a real compose screen behaves,
         *  as opposed to a plain "enter a name and save it" field. */
        const val EXTRA_SEND_MODE = "send_mode"
    }

    private lateinit var fieldText: EditText
    private lateinit var softLeftLabel: TextView
    private lateinit var suggestionsBar: TextView
    private lateinit var listeningIndicator: TextView
    private lateinit var softRightLabel: TextView
    private lateinit var inputController: T9InputController
    private var voiceHelper: GroqVoiceInputHelper? = null
    private var allowVoice = true
    private var sendMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_text_entry)

        ThemeHelper.apply(this)
        findViewById<TextView>(R.id.textEntryTitle).text = intent.getStringExtra(EXTRA_TITLE) ?: "Enter Text"
        allowVoice = intent.getBooleanExtra(EXTRA_ALLOW_VOICE, true)
        sendMode = intent.getBooleanExtra(EXTRA_SEND_MODE, false)
        softRightLabel = findViewById(R.id.softRightLabel2)
        if (sendMode) softRightLabel.setText(R.string.softkey_options)

        fieldText = findViewById(R.id.fieldText)
        fieldText.setShowSoftInputOnFocus(false)
        // Voice used to be triggered by the left softkey — now the
        // physical mic/assistant button does that instead, so this label
        // (id kept as softLeftLabel2 for layout compatibility) is free to
        // show the live typing-mode label instead of sitting unused.
        softLeftLabel = findViewById(R.id.softLeftLabel2)
        suggestionsBar = findViewById(R.id.suggestionsBar)
        listeningIndicator = findViewById(R.id.listeningIndicator)

        val engine = T9EngineHolder.get(this)
        inputController = T9InputController(
            engine = engine,
            outputView = fieldText,
            onModeChanged = { label -> softLeftLabel.text = label },
            onSuggestionsChanged = { candidates, selected, windowSize -> renderSuggestions(candidates, selected, windowSize) },
            suggestionsBarView = suggestionsBar,
            // Only a message has paragraphs; names and renames are one line.
            allowNewLines = sendMode
        )
        intent.getStringExtra(EXTRA_INITIAL_TEXT)?.let { inputController.setText(it) }
        // onModeChanged only fires on a change — without this the softkey
        // label would show nothing at all until the first keypress.
        softLeftLabel.text = inputController.currentLabel()
        inputController.startCursorBlink()
        fieldText.requestFocus()

        if (allowVoice) {
            voiceHelper = GroqVoiceInputHelper(this)
        }
    }

    /** Left softkey — jumps straight to a top-level typing mode instead of
     *  cycling '*' through it. */
    private fun showModeMenu() {
        val options = arrayOf("T9 Word", "ABC", "123")
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Typing Mode")
            .setItems(options) { _, which ->
                val newMode = when (which) {
                    0 -> InputMode.WORD
                    1 -> InputMode.MULTITAP
                    else -> InputMode.NUMBER
                }
                inputController.selectMode(newMode)
            }
            .show()
    }

    private fun renderSuggestions(candidates: List<String>, selected: Int, windowSize: Int = 5) {
        if (candidates.isEmpty()) {
            suggestionsBar.visibility = View.GONE
            return
        }
        suggestionsBar.visibility = View.VISIBLE
        suggestionsBar.text = SuggestionRenderer.build(candidates, selected, this, windowSize)
    }

    private fun saveAndClose() {
        val result = Intent()
        result.putExtra(EXTRA_RESULT_TEXT, inputController.currentText())
        setResult(RESULT_OK, result)
        finish()
    }

    private fun showOptions() {
        val options = arrayOf("Paste")
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Options")
            .setItems(options) { _, which ->
                if (options[which] == "Paste") pasteFromClipboard()
            }
            .show()
    }

    private fun pasteFromClipboard() {
        val clipboard = getSystemService(android.content.ClipboardManager::class.java)
        val clip = clipboard?.primaryClip
        if (clip != null && clip.itemCount > 0) {
            val pasted = clip.getItemAt(0).text?.toString()
            if (!pasted.isNullOrEmpty()) {
                inputController.setText(inputController.currentText() + pasted)
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_UP) {
            if (event.keyCode in MicButtonKeyCodes.CODES && allowVoice) {
                stopVoiceRecordingAndSend()
                return true
            }
            if (inputController.onKeyUp(event.keyCode)) return true
            return true
        }

        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)

        if (event.keyCode == KeyEvent.KEYCODE_SOFT_RIGHT) {
            if (sendMode) showOptions() else saveAndClose()
            return true
        }
        if (event.keyCode == KeyEvent.KEYCODE_SOFT_LEFT) {
            showModeMenu()
            return true
        }
        if (event.keyCode == KeyEvent.KEYCODE_CALL && sendMode) {
            saveAndClose()
            return true
        }
        if (event.keyCode in MicButtonKeyCodes.CODES) {
            if (allowVoice && event.repeatCount == 0) startVoiceRecording()
            return true
        }
        if (event.keyCode == KeyEvent.KEYCODE_DEL || event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (inputController.hasContent()) {
                inputController.onKeyDown(KeyEvent.KEYCODE_DEL, event)
            } else {
                setResult(RESULT_CANCELED)
                finish()
            }
            return true
        }

        if (inputController.onKeyDown(event.keyCode, event)) return true
        return true
    }

    private fun startVoiceRecording() {
        val helper = voiceHelper ?: return
        if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            androidx.core.app.ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.RECORD_AUDIO), 200)
            return
        }
        if (helper.startRecording()) {
            listeningIndicator.text = "Recording…"
            listeningIndicator.visibility = View.VISIBLE
        }
    }

    private fun stopVoiceRecordingAndSend() {
        val helper = voiceHelper ?: return
        listeningIndicator.text = "Transcribing…"
        helper.stopRecordingAndTranscribe(
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
        voiceHelper?.cancelRecording()
        inputController.stopCursorBlink()
    }
}
