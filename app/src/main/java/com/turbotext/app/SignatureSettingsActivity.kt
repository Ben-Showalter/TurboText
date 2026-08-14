package com.turbotext.app

import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class SignatureSettingsActivity : AppCompatActivity() {

    private lateinit var signatureToggleRow: TextView
    private lateinit var signatureText: EditText
    private lateinit var modeIndicator: TextView
    private lateinit var listeningIndicator: TextView
    private lateinit var inputController: T9InputController
    private lateinit var voiceHelper: GroqVoiceInputHelper
    private var signatureEnabled = false

    // 0 = toggle row, 1 = signature text field.
    private var currentRow = 0
    private val lastRow = 1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_signature_settings)

        ThemeHelper.apply(this)
        signatureToggleRow = findViewById(R.id.signatureToggleRow)
        signatureText = findViewById(R.id.signatureText)
        signatureText.setShowSoftInputOnFocus(false)
        modeIndicator = findViewById(R.id.modeIndicator)
        listeningIndicator = findViewById(R.id.listeningIndicator)

        signatureToggleRow.setOnClickListener { toggleSignature() }

        signatureEnabled = SettingsHelper.isSignatureEnabled(this)
        updateSignatureToggleLabel()

        val engine = T9EngineHolder.get(this)
        inputController = T9InputController(
            engine = engine,
            outputView = signatureText,
            onModeChanged = { mode -> modeIndicator.text = mode.label },
        )
        inputController.setText(SettingsHelper.getSignatureText(this))
        voiceHelper = GroqVoiceInputHelper(this)

        updateRowHighlight()
    }

    private fun toggleSignature() {
        signatureEnabled = !signatureEnabled
        updateSignatureToggleLabel()
    }

    private fun updateSignatureToggleLabel() {
        signatureToggleRow.text = "Signature: ${if (signatureEnabled) "On" else "Off"}"
    }

    private fun updateRowHighlight() {
        signatureToggleRow.setBackgroundColor(if (currentRow == 0) ThemeHelper.getCurrentTheme(this).surface2 else android.graphics.Color.TRANSPARENT)
        if (currentRow == 1) {
            inputController.startCursorBlink()
        } else {
            inputController.stopCursorBlink()
        }
    }

    private fun saveAndClose() {
        SettingsHelper.setSignature(this, signatureEnabled, inputController.currentText())
        Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show()
        finish()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) {
            if (event.action == KeyEvent.ACTION_UP) {
                if (event.keyCode in MicButtonKeyCodes.CODES) {
                    stopVoiceRecordingAndSend()
                    return true
                }
                if (currentRow == lastRow && inputController.onKeyUp(event.keyCode)) return true
            }
            return super.dispatchKeyEvent(event)
        }

        if (event.keyCode == KeyEvent.KEYCODE_SOFT_RIGHT) {
            saveAndClose()
            return true
        }
        if (event.keyCode in MicButtonKeyCodes.CODES) {
            if (event.repeatCount == 0) startVoiceRecording()
            return true
        }

        if (currentRow == lastRow) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> {
                    if (inputController.hasPendingWord()) {
                        inputController.onKeyDown(event.keyCode, event)
                    } else {
                        currentRow--
                        updateRowHighlight()
                    }
                    return true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (inputController.hasPendingWord()) {
                        inputController.onKeyDown(event.keyCode, event)
                    }
                    return true
                }
                KeyEvent.KEYCODE_BACK -> {
                    if (inputController.hasContent()) {
                        inputController.onKeyDown(KeyEvent.KEYCODE_DEL, event)
                    } else {
                        saveAndClose()
                    }
                    return true
                }
                else -> {
                    if (inputController.onKeyDown(event.keyCode, event)) return true
                }
            }
            return true
        }

        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (currentRow > 0) {
                    currentRow--
                    updateRowHighlight()
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (currentRow < lastRow) {
                    currentRow++
                    updateRowHighlight()
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER -> {
                toggleSignature()
                return true
            }
            KeyEvent.KEYCODE_BACK -> {
                saveAndClose()
                return true
            }
        }
        return super.dispatchKeyEvent(event)
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
                if (text.isNotEmpty()) {
                    currentRow = lastRow
                    updateRowHighlight()
                    inputController.appendVoiceResult(text)
                }
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
