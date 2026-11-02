package com.turbotext.app

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Two-stage compose: first the recipient number (numeric keypad, no T9
 * needed there), then the message body (full T9 + voice + photo). DPAD_CENTER
 * / the OK key moves from the "To" field to the body once a number is entered.
 */
class ComposeActivity : AppCompatActivity() {

    companion object {
        /** Forwarding: an attachment (content:// URI + MIME type) to
         *  start the new message with. */
        const val EXTRA_PREFILL_ATTACHMENT_URI = "prefillAttachmentUri"
        const val EXTRA_PREFILL_ATTACHMENT_MIME = "prefillAttachmentMime"
        private const val REQ_CONTACT = 620
    }

    private lateinit var repo: SmsRepository
    private lateinit var engine: T9Engine
    private lateinit var inputController: T9InputController
    private lateinit var recipientController: T9InputController
    private lateinit var voiceHelper: GroqVoiceInputHelper
    private lateinit var toText: EditText
    private lateinit var composeText: EditText
    private lateinit var softLeftLabel: TextView
    private lateinit var suggestionsBar: TextView
    private lateinit var attachmentIndicator: TextView
    private lateinit var listeningIndicator: TextView

    private var editingRecipient = true
    private var recipientMode = InputMode.NUMBER
    private lateinit var attachments: PendingAttachments
    private lateinit var picker: AttachmentPicker
    private var sending = false
    private lateinit var audioMemoRecorder: AudioMemoRecorder
    private var isRecordingMemo = false


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_compose)

        ThemeHelper.apply(this)
        repo = SmsRepository(this)
        engine = T9EngineHolder.get(this)
        toText = findViewById(R.id.toText)
        toText.setShowSoftInputOnFocus(false)
        composeText = findViewById(R.id.composeText)
        // The phone's own Paste (its copy/paste tool) goes into the T9
        // typing of whichever box it targets.
        (composeText as? NoImeEditText)?.apply {
            onPaste = { pasteFromClipboard() }
            onSetText = { text -> inputController.setText(text) }
        }
        (toText as? NoImeEditText)?.apply {
            onPaste = { ClipboardPaste.request(this@ComposeActivity) { text -> recipientController.appendVoiceResult(text) } }
            onSetText = { text -> recipientController.setText(text) }
        }
        composeText.movementMethod = android.text.method.ScrollingMovementMethod()
        composeText.setShowSoftInputOnFocus(false)
        composeText.hint = HintHelper.dictateHint(composeText)
        softLeftLabel = findViewById(R.id.softLeftLabel)
        suggestionsBar = findViewById(R.id.suggestionsBar)
        attachmentIndicator = findViewById(R.id.attachmentIndicator)
        listeningIndicator = findViewById(R.id.listeningIndicator)

        recipientController = T9InputController(
            engine = engine,
            outputView = toText,
            onModeChanged = { label ->
                recipientMode = recipientController.currentMode()
                if (editingRecipient) softLeftLabel.text = label
            },
            allowNewLines = false,
            onSuggestionsChanged = { candidates, selected, windowSize ->
                if (editingRecipient) renderSuggestions(candidates, selected, windowSize)
            },
            // A number is typed here; contacts come from the phone's own
            // contact picker (askHowToAddress / Options > Choose Contact).
            // '*' still reaches Abc for an email address.
            initialMode = InputMode.NUMBER,
            learnsWords = false,
            previewRawDigits = true,
            suggestionsBarView = suggestionsBar
        )

        // Pre-fill recipient if launched from an sms: link
        intent?.data?.schemeSpecificPart?.let {
            recipientController.setText(it)
            editingRecipient = false
            composeText.post { composeText.requestFocus() }
        }

        inputController = T9InputController(
            engine = engine,
            outputView = composeText,
            onModeChanged = { label -> if (!editingRecipient) softLeftLabel.text = label },
            onSuggestionsChanged = { candidates, selected, windowSize -> renderSuggestions(candidates, selected, windowSize) },
            suggestionsBarView = suggestionsBar
        )
        if (!editingRecipient) inputController.startCursorBlink() else recipientController.startCursorBlink()
        softLeftLabel.text = if (editingRecipient) recipientController.currentLabel() else inputController.currentLabel()

        // Forwarding a message launches here with these extras — recipient
        // still needs to be chosen, so this doesn't touch editingRecipient.
        intent?.getStringExtra("prefillText")?.let { inputController.setText(it) }
        voiceHelper = GroqVoiceInputHelper(this)
        audioMemoRecorder = AudioMemoRecorder(this)
        attachments = PendingAttachments(this, attachmentIndicator)
        picker = AttachmentPicker(this) { attachments.add(it) }

        // Shared in from another app ("Share → TurboText").
        if (intent?.action == android.content.Intent.ACTION_SEND) {
            intent.getStringExtra(android.content.Intent.EXTRA_TEXT)?.let { inputController.setText(it) }
            @Suppress("DEPRECATION")
            (intent.getParcelableExtra<Uri>(android.content.Intent.EXTRA_STREAM))?.let {
                attachments.add(listOf(OutgoingAttachment.fromUri(this, it, intent.type ?: "application/octet-stream")))
            }
        }
        // Several photos shared at once.
        if (intent?.action == android.content.Intent.ACTION_SEND_MULTIPLE) {
            intent.getStringExtra(android.content.Intent.EXTRA_TEXT)?.let { inputController.setText(it) }
            @Suppress("DEPRECATION")
            intent.getParcelableArrayListExtra<Uri>(android.content.Intent.EXTRA_STREAM)?.let { uris ->
                attachments.add(uris.map { OutgoingAttachment.fromUri(this, it, intent.type ?: "application/octet-stream") })
            }
        }

        val prefillUri = intent?.getStringExtra(EXTRA_PREFILL_ATTACHMENT_URI)
            ?: intent?.getStringExtra("prefillImageUri")
        if (prefillUri != null) {
            val mime = intent?.getStringExtra(EXTRA_PREFILL_ATTACHMENT_MIME) ?: "image/jpeg"
            val ext = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "bin"
            attachments.add(listOf(OutgoingAttachment(mime, "forwarded_${System.currentTimeMillis()}.$ext", uri = Uri.parse(prefillUri))))
        }

        if (savedInstanceState == null && !recipientController.hasContent()) askHowToAddress()
    }

    private fun renderSuggestions(candidates: List<String>, selected: Int, windowSize: Int = 5) {
        if (candidates.isEmpty()) {
            suggestionsBar.visibility = View.GONE
            return
        }
        suggestionsBar.visibility = View.VISIBLE
        suggestionsBar.text = SuggestionRenderer.build(candidates, selected, this, windowSize)
    }

    private fun startVoiceRecording() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 200)
            return
        }
        if (voiceHelper.startRecording()) {
            listeningIndicator.text = "Recording…"
            listeningIndicator.visibility = View.VISIBLE
        }
    }

    private fun stopVoiceRecordingAndSend() {
        // Which field gets the transcribed text depends on which stage
        // is active when recording started — captured now (recording
        // already happened in the past) rather than re-checked once
        // transcription comes back, in case the user already advanced
        // to the body while waiting.
        val recipientStage = editingRecipient
        listeningIndicator.text = "Transcribing…"
        voiceHelper.stopRecordingAndTranscribe(
            onResult = { text ->
                listeningIndicator.visibility = View.GONE
                if (text.isNotEmpty()) {
                    if (recipientStage) {
                        recipientController.appendVoiceResult(text)
                    } else {
                        inputController.appendVoiceResult(text)
                    }
                }
            },
            onError = { message ->
                listeningIndicator.visibility = View.GONE
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            }
        )
    }

    /** Left softkey — jumps straight to a top-level typing mode instead of
     *  cycling '*' through it. Targets whichever field is actually active
     *  (recipient vs. body), same split showOptions() uses. */
    private fun showModeMenu() {
        val controller = if (editingRecipient) recipientController else inputController
        val options = arrayOf("T9 Word", "ABC", "123")
        AlertDialog.Builder(this)
            .setTitle("Typing Mode")
            .setItems(options) { _, which ->
                val newMode = when (which) {
                    0 -> InputMode.WORD
                    1 -> InputMode.MULTITAP
                    else -> InputMode.NUMBER
                }
                controller.selectMode(newMode)
            }
            .show()
    }

    private fun showOptions() {
        val options = arrayOf("Choose Contact") + when {
            editingRecipient -> emptyArray()
            !attachments.isEmpty() ->
                arrayOf("Choose Picture or Video", "Take Photo", "Record Audio", "Attach", "Remove Attachment", "Paste", "Send Later")
            else -> arrayOf("Choose Picture or Video", "Take Photo", "Record Audio", "Attach", "Paste", "Send Later")
        }
        AlertDialog.Builder(this)
            .setTitle("Options")
            .setItems(options) { _, which ->
                when (options[which]) {
                    "Choose Contact" -> chooseContact()
                    "Send Later" -> scheduleMessage()
                    "Choose Picture or Video" -> picker.pickFromGallery()
                    "Take Photo" -> picker.takePhoto()
                    "Record Audio" -> startAudioMemoRecording()
                    "Attach" -> picker.showAttachMenu()
                    "Remove Attachment" -> {
                        attachments.clear()
                        Toast.makeText(this, "Attachment removed", Toast.LENGTH_SHORT).show()
                    }
                    "Paste" -> pasteFromClipboard()
                }
            }
            .show()
    }

    private fun startAudioMemoRecording() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 402)
            return
        }
        if (audioMemoRecorder.startRecording()) {
            isRecordingMemo = true
            listeningIndicator.text = "Recording audio… Press OK to finish"
            listeningIndicator.visibility = View.VISIBLE
        } else {
            Toast.makeText(this, "Couldn't start recording", Toast.LENGTH_SHORT).show()
        }
    }

    private fun finishAudioMemoRecording() {
        val uri = audioMemoRecorder.stopRecording()
        isRecordingMemo = false
        listeningIndicator.visibility = View.GONE
        if (uri != null) {
            attachments.add(listOf(OutgoingAttachment("audio/mp4", "audio_${System.currentTimeMillis()}.m4a", uri = uri)))
        } else {
            Toast.makeText(this, "Recording failed", Toast.LENGTH_SHORT).show()
        }
    }

    /** Waits for this screen to have focus again (ClipboardPaste) —
     *  Android won't hand the clipboard over while the menu is closing. */
    private fun pasteFromClipboard() {
        ClipboardPaste.request(this) { text -> inputController.appendVoiceResult(text) }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) ClipboardPaste.onWindowFocus(this)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_CONTACT) {
            val uri = data?.data
            if (resultCode == android.app.Activity.RESULT_OK && uri != null) {
                val number = try {
                    contentResolver.query(
                        uri, arrayOf(android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER), null, null, null
                    )?.use { if (it.moveToFirst()) it.getString(0) else null }
                } catch (e: Exception) {
                    null
                }
                if (number.isNullOrBlank()) {
                    Toast.makeText(this, "Couldn't read that contact's number", Toast.LENGTH_SHORT).show()
                } else {
                    recipientController.setText(number)
                    advanceToBody()
                }
            }
            return
        }
        picker.handleResult(requestCode, resultCode, data)
    }

    /** New Message starts by asking how to pick who it's to. Backing out
     *  of the question closes New Message. */
    private fun askHowToAddress() {
        AlertDialog.Builder(this)
            .setTitle("New Message")
            .setItems(arrayOf("Choose Contact", "Type Number")) { _, which ->
                if (which == 0) chooseContact()
            }
            .setOnCancelListener { finish() }
            .show()
    }

    /** The phone's own contact picker, one number. */
    private fun chooseContact() {
        val intent = android.content.Intent(
            android.content.Intent.ACTION_PICK, android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        )
        try {
            startActivityForResult(intent, REQ_CONTACT)
        } catch (e: Exception) {
            Toast.makeText(this, "No contacts app found; type the number", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * From the "To:" field to the message. If this person already has a
     * conversation, it opens instead, so the message is written there,
     * taking along anything already typed or attached.
     */
    private fun advanceToBody() {
        val address = recipientController.currentText().trim()
        val app = applicationContext
        Thread {
            val threadId = MessageSender.threadIdFor(app, listOf(address))
            val existing = threadId >= 0 && repo.threadHasMessages(threadId)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (existing) {
                    openThread(threadId, address)
                } else {
                    editingRecipient = false
                    recipientController.stopCursorBlink()
                    inputController.startCursorBlink()
                    softLeftLabel.text = inputController.currentLabel()
                    composeText.post { composeText.requestFocus() }
                }
            }
        }.start()
    }

    /** Opens [threadId] with this screen's text and attachments in its
     *  compose box, and closes this one. */
    private fun openThread(threadId: Long, address: String) {
        inputController.confirmPending()
        val text = inputController.currentText()
        val handoff = OutgoingAttachment.toHandoff(this, attachments.items)
        startActivity(
            android.content.Intent(this, ConversationActivity::class.java)
                .putExtra("threadId", threadId)
                .putExtra("address", address)
                .putExtra("displayName", MessageSender.displayNameFor(this, listOf(address)))
                .putExtra(ConversationActivity.EXTRA_PREFILL_TEXT, text)
                .putStringArrayListExtra(ConversationActivity.EXTRA_PREFILL_ATTACHMENTS, handoff)
        )
        finish()
    }

    /** Options > Send Later: like Send, on a day and at a time picked on the keypad. */
    private fun scheduleMessage() {
        recipientController.confirmPending()
        inputController.confirmPending()
        val address = recipientController.currentText().trim()
        val body = SettingsHelper.applySignature(this, inputController.currentText().trim())
        val toSchedule = attachments.items
        if (address.isEmpty()) {
            Toast.makeText(this, "Enter a recipient first", Toast.LENGTH_SHORT).show()
            return
        }
        if (body.isEmpty() && toSchedule.isEmpty()) {
            Toast.makeText(this, "Write a message first", Toast.LENGTH_SHORT).show()
            return
        }
        SendLaterPicker.pick(this, null) { at ->
            val app = applicationContext
            sending = true
            Thread {
                val recipients = listOf(address)
                val threadId = MessageSender.threadIdFor(app, recipients)
                val entry = if (threadId >= 0) ScheduledSends.schedule(app, threadId, recipients, body, toSchedule, at) else null
                val name = MessageSender.displayNameFor(app, recipients)
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    sending = false
                    if (entry == null) {
                        Toast.makeText(this, "Couldn't schedule that message", Toast.LENGTH_LONG).show()
                        return@runOnUiThread
                    }
                    Toast.makeText(this, "Will send ${ScheduleLabels.whenText(at)}", Toast.LENGTH_SHORT).show()
                    startActivity(
                        android.content.Intent(this, ConversationActivity::class.java)
                            .putExtra("threadId", threadId)
                            .putExtra("address", address)
                            .putExtra("displayName", name)
                    )
                    finish()
                }
            }.start()
        }
    }

    private fun sendMessage() {
        if (sending) return
        recipientController.confirmPending()
        // A word still showing as a T9 suggestion goes out as shown, as if
        // OK had been pressed first.
        inputController.confirmPending()
        val address = recipientController.currentText().trim()
        val body = SettingsHelper.applySignature(this, inputController.currentText().trim())
        val toSend = attachments.items

        if (address.isEmpty()) {
            Toast.makeText(this, "Enter a recipient first", Toast.LENGTH_SHORT).show()
            return
        }
        if (body.isEmpty() && toSend.isEmpty()) {
            Toast.makeText(this, "Nothing to send", Toast.LENGTH_SHORT).show()
            return
        }

        sending = true
        val app = applicationContext
        Thread {
            val recipients = listOf(address)
            val threadId = MessageSender.threadIdFor(app, recipients)
            val name = MessageSender.displayNameFor(app, recipients)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (threadId < 0) {
                    // Couldn't find the thread to open: send from here and
                    // stay put, as before.
                    PendingSends.send(app, threadId, recipients, body, toSend, onDone = { error ->
                        if (isFinishing || isDestroyed) return@send
                        sending = false
                        if (error != null) Toast.makeText(this, error, Toast.LENGTH_LONG).show() else finish()
                    })
                    return@runOnUiThread
                }
                // The send carries on in the background; the thread opens
                // straight away with a "Sending" bubble that turns into
                // Sent/Delivered there (or puts the text back if it fails).
                PendingSends.send(app, threadId, recipients, body, toSend, onDone = { error ->
                    if (error != null) Toast.makeText(app, error, Toast.LENGTH_LONG).show()
                })
                startActivity(
                    android.content.Intent(this, ConversationActivity::class.java)
                        .putExtra("threadId", threadId)
                        .putExtra("address", address)
                        .putExtra("displayName", name)
                )
                finish()
            }
        }.start()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            if (isRecordingMemo) {
                if (event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
                    finishAudioMemoRecording()
                }
                return true
            }
            when (event.keyCode) {
                KeyEvent.KEYCODE_CALL -> { sendMessage(); return true }
                KeyEvent.KEYCODE_SOFT_RIGHT -> {
                    showOptions()
                    return true
                }
                KeyEvent.KEYCODE_SOFT_LEFT -> {
                    showModeMenu()
                    return true
                }
            }
            if (event.keyCode in MicButtonKeyCodes.CODES) {
                if (event.repeatCount == 0) startVoiceRecording()
                return true
            }

            if (editingRecipient) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        recipientController.onKeyDown(event.keyCode, event)
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                        // The punctuation picker owns Center itself (it
                        // inserts the highlighted mark) — forwarded
                        // straight to the controller rather than going
                        // through the advance-to-body logic below, which
                        // would otherwise swallow the press without ever
                        // inserting the mark.
                        if (recipientController.isPunctuationPickerActive()) {
                            recipientController.onKeyDown(event.keyCode, event)
                            return true
                        }
                        // Handled directly rather than delegated —
                        // T9InputController's own DPAD_CENTER handling
                        // (confirmWord) would otherwise always consume
                        // this before the advance-to-body check below
                        // ever got a chance to run. confirmPending()
                        // does that confirm step manually instead, so a
                        // candidate still only in preview isn't silently
                        // dropped.
                        recipientController.confirmPending()
                        if (recipientController.hasContent()) advanceToBody()
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        // Mid-pick, Down shouldn't jump straight to the
                        // body and abandon the mark being highlighted.
                        if (recipientController.isPunctuationPickerActive()) {
                            recipientController.onKeyDown(event.keyCode, event)
                            return true
                        }
                        recipientController.confirmPending()
                        if (recipientController.hasContent()) advanceToBody()
                        return true
                    }
                    // This phone's physical Clear key sends KEYCODE_BACK,
                    // not KEYCODE_DEL, so both are handled the same way.
                    KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_BACK -> {
                        if (recipientController.hasContent()) {
                            recipientController.onKeyDown(KeyEvent.KEYCODE_DEL, event)
                        } else {
                            finish()
                        }
                        return true
                    }
                    else -> {
                        // Digits, *, # (mode cycling / space), and anything
                        // else T9InputController recognizes — handles both
                        // phone-number digit entry (NUMBER mode) and email
                        // letter entry (MULTITAP mode, reached by pressing *).
                        if (recipientController.onKeyDown(event.keyCode, event)) return true
                    }
                }
                return super.dispatchKeyEvent(event)
            }

            // Body stage
            if (event.keyCode == KeyEvent.KEYCODE_DEL || event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (inputController.hasContent()) {
                    inputController.onKeyDown(KeyEvent.KEYCODE_DEL, event)
                } else {
                    editingRecipient = true
                    inputController.stopCursorBlink()
                    recipientController.startCursorBlink()
                    softLeftLabel.text = recipientController.currentLabel()
                    // NoImeEditText's cursor overlay only draws while its
                    // view is focused (see its onFocusChanged/cursorDrawable)
                    // — the forward transition below moves focus to
                    // composeText itself, but nothing was ever moving it
                    // back to toText here, so the cursor stayed invisible
                    // even with startCursorBlink() called above.
                    toText.post { toText.requestFocus() }
                }
                return true
            }
            // Up moves the cursor up a line within the message, same as
            // in-thread composing (ConversationActivity's own DPAD_UP
            // handling) — only once there's no line above to go to
            // (moveCursorLineUp returns false) does it back out of the
            // compose box. There's no message list on this screen to land
            // on, so it backs out to the "To:" field instead.
            if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                if (inputController.hasPendingWord()) {
                    inputController.onKeyDown(event.keyCode, event)
                } else if (!inputController.moveCursorLineUp()) {
                    editingRecipient = true
                    inputController.stopCursorBlink()
                    recipientController.startCursorBlink()
                    softLeftLabel.text = recipientController.currentLabel()
                    toText.post { toText.requestFocus() }
                }
                return true
            }
            // Down moves the cursor down a line the same way — mirroring
            // ConversationActivity, which never exits the compose box on
            // Down either (there's nothing below it to exit to there, same
            // as here).
            if (event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                if (inputController.hasPendingWord()) {
                    inputController.onKeyDown(event.keyCode, event)
                } else {
                    inputController.moveCursorLineDown()
                }
                return true
            }
            if (inputController.onKeyDown(event.keyCode, event)) return true
        } else if (event.action == KeyEvent.ACTION_UP) {
            if (event.keyCode in MicButtonKeyCodes.CODES) {
                stopVoiceRecordingAndSend()
                return true
            }
            if (editingRecipient && recipientController.onKeyUp(event.keyCode)) return true
            if (!editingRecipient && inputController.onKeyUp(event.keyCode)) return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onDestroy() {
        super.onDestroy()
        voiceHelper.cancelRecording()
        audioMemoRecorder.cancelRecording()
        inputController.stopCursorBlink()
        recipientController.stopCursorBlink()
    }
}
