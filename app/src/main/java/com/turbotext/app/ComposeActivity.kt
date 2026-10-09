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
    private var recipientMode = InputMode.WORD
    private lateinit var attachments: PendingAttachments
    private lateinit var picker: AttachmentPicker
    private var sending = false
    private lateinit var audioMemoRecorder: AudioMemoRecorder
    private var isRecordingMemo = false

    private data class ContactMatch(val name: String, val number: String)
    private var allContacts: List<ContactMatch> = emptyList()
    private var contactMatches: List<ContactMatch> = emptyList()
    private var contactMatchIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_compose)

        ThemeHelper.apply(this)
        repo = SmsRepository(this)
        engine = T9EngineHolder.get(this)
        toText = findViewById(R.id.toText)
        toText.setShowSoftInputOnFocus(false)
        composeText = findViewById(R.id.composeText)
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
            // Starts in Word mode (T9 predictive / choose-from-contacts) —
            // the default the user wants for the "To:" field. '*' cycles
            // Word -> Number -> Abc -> (Emoji) -> Word; the raw-digits
            // fallback candidate (see contactNameCandidatesFor) still keeps
            // a typed number available even while browsing Word matches.
            initialMode = InputMode.WORD,
            candidateProvider = { digits -> contactNameCandidatesFor(digits) },
            resolveWord = { candidate -> resolveRecipientWord(candidate) },
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

        Thread {
            allContacts = loadAllContacts()
        }.start()
    }

    private fun loadAllContacts(): List<ContactMatch> {
        val results = mutableListOf<ContactMatch>()
        try {
            val phoneCursor = contentResolver.query(
                android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER
                ),
                null, null, null
            )
            phoneCursor?.use {
                while (it.moveToNext()) {
                    val name = it.getString(0) ?: continue
                    val number = it.getString(1) ?: continue
                    results.add(ContactMatch(name, number))
                }
            }
            val emailCursor = contentResolver.query(
                android.provider.ContactsContract.CommonDataKinds.Email.CONTENT_URI,
                arrayOf(
                    android.provider.ContactsContract.CommonDataKinds.Email.DISPLAY_NAME,
                    android.provider.ContactsContract.CommonDataKinds.Email.ADDRESS
                ),
                null, null, null
            )
            emailCursor?.use {
                while (it.moveToNext()) {
                    val name = it.getString(0) ?: continue
                    val address = it.getString(1) ?: continue
                    results.add(ContactMatch(name, address))
                }
            }
        } catch (e: Exception) {
            // Missing permission or other failure — plain number entry
            // still works fine without contact matching.
        }
        return results
    }

    /** WORD mode's native candidate list for the recipient field — matches
     *  typed digits against contact names by T9 digit code first (a full
     *  "First Last" match, so picking it can resolve to that contact's
     *  number/email below), then falls back to the same dictionary word
     *  list the message body uses. That second part is what makes typing
     *  an email address in Word mode possible at all — "gmail", "com",
     *  etc. aren't contact names, just ordinary predictive words — with
     *  the raw digit sequence always available as the very last fallback
     *  candidate. */
    private fun contactNameCandidatesFor(digits: String): List<String> {
        val names = LinkedHashSet<String>()
        for (contact in allContacts) {
            for (word in contact.name.split(Regex("\\s+"))) {
                if (engine.digitCodeFor(word).startsWith(digits)) {
                    names.add(contact.name)
                    break
                }
            }
        }
        val result = names.take(10).toMutableList()
        for (word in engine.candidatesFor(digits)) {
            if (result.size >= 15) break
            if (!result.contains(word)) result.add(word)
        }
        if (!result.contains(digits)) result.add(digits)
        return result
    }

    /** Turns a confirmed candidate into what actually gets sent — a
     *  matched contact name resolves to that contact's number/email;
     *  anything else (the raw-digits fallback, or digits typed with no
     *  match at all) passes through unchanged. Ambiguous duplicate names
     *  resolve to whichever contact was found first — a rare edge case,
     *  not worth a disambiguation UI for. */
    private fun resolveRecipientWord(candidate: String): String =
        allContacts.firstOrNull { it.name == candidate }?.number ?: candidate

    private fun renderSuggestions(candidates: List<String>, selected: Int, windowSize: Int = 5) {
        if (candidates.isEmpty()) {
            suggestionsBar.visibility = View.GONE
            return
        }
        suggestionsBar.visibility = View.VISIBLE
        suggestionsBar.text = SuggestionRenderer.build(candidates, selected, this, windowSize)
    }

    /** Only relevant in MULTITAP (letters) mode now — WORD mode's own
     *  native candidates (contactNameCandidatesFor above) handle name
     *  search by digit code natively. This covers email specifically:
     *  matches typed letters against contact names and email addresses
     *  directly, since emails aren't reachable through T9 digit codes at
     *  all. contactMatches stays empty outside MULTITAP mode, so the
     *  Left/Right/Center handling in dispatchKeyEvent naturally falls
     *  through to the recipientController's own native handling there.
     *  Note this early-return deliberately skips renderContactMatches():
     *  in WORD/NUMBER mode the suggestions bar is already being driven by
     *  T9InputController's own onSuggestionsChanged callback (fired from
     *  the onKeyDown call that runs right before this), so calling
     *  renderContactMatches() here — which hides the bar whenever
     *  contactMatches is empty, which it always is outside MULTITAP —
     *  would immediately hide the WORD-mode contact dropdown that call
     *  just showed. */
    private fun updateContactMatches() {
        if (recipientMode != InputMode.MULTITAP) {
            contactMatches = emptyList()
            return
        }
        contactMatches = contactsMatchingTypedText(recipientController.currentText())
        contactMatchIndex = 0
        renderContactMatches()
    }

    /** Same literal-text matching updateContactMatches() does for
     *  MULTITAP, but usable regardless of the current mode — voice
     *  dictation into the "To:" field (see stopVoiceRecordingAndSend)
     *  inserts whatever was spoken as literal text via appendVoiceResult,
     *  completely bypassing T9 digit-code entry (WORD mode's own
     *  contactNameCandidatesFor), so it needs its own path to surface
     *  matches — updateContactMatches()'s mode gate would otherwise
     *  silently drop a dictated name with no dropdown at all whenever
     *  WORD mode (the default for this field) is active. */
    private fun updateContactMatchesFromVoice() {
        contactMatches = contactsMatchingTypedText(recipientController.currentText())
        contactMatchIndex = 0
        renderContactMatches()
    }

    /** Matches spoken/typed text against contact names word-by-word rather
     *  than as one literal phrase — voice dictation and MULTITAP both hand
     *  this a whole name in one shot (e.g. "John Smith"), and no single
     *  word in a contact's name is ever a prefix of that entire two-word
     *  string. Each typed word just needs to prefix some later word in the
     *  contact's name, in order (so "John Michael Smith" still matches
     *  typing "john smith") — a single typed word still matches as before. */
    private fun contactsMatchingTypedText(typed: String): List<ContactMatch> {
        if (typed.isBlank()) return emptyList()
        val typedWords = typed.trim().lowercase().split(Regex("\\s+"))
        return allContacts.filter { contact ->
            if (contact.number.lowercase().startsWith(typed.lowercase())) return@filter true
            val nameWords = contact.name.lowercase().split(Regex("\\s+"))
            var searchFrom = 0
            for (word in typedWords) {
                val foundAt = (searchFrom until nameWords.size).firstOrNull { nameWords[it].startsWith(word) }
                    ?: return@filter false
                searchFrom = foundAt + 1
            }
            true
        }.take(10)
    }

    private fun renderContactMatches() {
        if (contactMatches.isEmpty()) {
            suggestionsBar.visibility = View.GONE
            return
        }
        suggestionsBar.visibility = View.VISIBLE
        // Marks email options so two matches sharing the same contact
        // name (one phone, one email) aren't indistinguishable.
        val labels = contactMatches.map { if (it.number.contains("@")) "${it.name} ✉" else it.name }
        suggestionsBar.text = SuggestionRenderer.build(labels, contactMatchIndex, this)
    }

    /** Fills in the recipient field from a chosen contact — doesn't
     *  advance to the compose box on its own; Down (or Center again, with
     *  the dropdown now cleared) does that separately. */
    private fun selectContact(contact: ContactMatch) {
        recipientController.setText(contact.number)
        contactMatches = emptyList()
        suggestionsBar.visibility = View.GONE
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
                        // Not updateContactMatches() — that only matches in
                        // MULTITAP mode (WORD mode's own T9 digit-code
                        // candidates handle typing there instead), but
                        // dictation inserts literal text regardless of
                        // mode, so it needs the mode-independent path.
                        updateContactMatchesFromVoice()
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
        if (editingRecipient) return
        val options = if (!attachments.isEmpty()) {
            arrayOf("Take Photo", "Record Video", "Attach", "Remove Attachment", "Paste")
        } else {
            arrayOf("Take Photo", "Record Video", "Attach", "Paste")
        }
        AlertDialog.Builder(this)
            .setTitle("Options")
            .setItems(options) { _, which ->
                when (options[which]) {
                    "Take Photo" -> picker.takePhoto()
                    "Record Video" -> picker.recordVideo()
                    "Attach" -> picker.showAttachMenu { startAudioMemoRecording() }
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

    private fun pasteFromClipboard() {
        val clipboard = getSystemService(android.content.ClipboardManager::class.java)
        val clip = clipboard.primaryClip
        if (clip == null || clip.itemCount == 0) {
            Toast.makeText(this, "Clipboard is empty", Toast.LENGTH_SHORT).show()
            return
        }
        val text = clip.getItemAt(0).coerceToText(this).toString()
        if (text.isEmpty()) {
            Toast.makeText(this, "Clipboard is empty", Toast.LENGTH_SHORT).show()
            return
        }
        inputController.appendVoiceResult(text)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        picker.handleResult(requestCode, resultCode, data)
    }

    private fun sendMessage() {
        if (sending) return
        recipientController.confirmPending()
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
        if (toSend.isNotEmpty()) {
            listeningIndicator.text = if (toSend.any { it.isVideo }) "Compressing video…" else "Preparing attachment…"
            listeningIndicator.visibility = View.VISIBLE
        }
        Thread {
            // MessageSender picks SMS or MMS (attachments and email
            // addresses go out as MMS).
            val error = MessageSender.send(this, listOf(address), body, toSend) { status ->
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        listeningIndicator.text = status
                        listeningIndicator.visibility = View.VISIBLE
                    }
                }
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                sending = false
                listeningIndicator.visibility = View.GONE
                if (error != null) {
                    Toast.makeText(this, error, Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, "Sent", Toast.LENGTH_SHORT).show()
                    finish()
                }
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
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        // The punctuation picker (open via '1') needs Left
                        // to browse its own marks — checked first so it
                        // isn't shadowed by a leftover contact dropdown
                        // (contactMatches isn't cleared just because the
                        // picker opened on top of it).
                        if (recipientController.isPunctuationPickerActive()) {
                            recipientController.onKeyDown(event.keyCode, event)
                        } else if (contactMatches.isNotEmpty()) {
                            contactMatchIndex = (contactMatchIndex - 1 + contactMatches.size) % contactMatches.size
                            renderContactMatches()
                        } else {
                            recipientController.onKeyDown(event.keyCode, event)
                        }
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        if (recipientController.isPunctuationPickerActive()) {
                            recipientController.onKeyDown(event.keyCode, event)
                        } else if (contactMatches.isNotEmpty()) {
                            contactMatchIndex = (contactMatchIndex + 1) % contactMatches.size
                            renderContactMatches()
                        } else {
                            recipientController.onKeyDown(event.keyCode, event)
                        }
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
                            updateContactMatches()
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
                        if (contactMatches.isNotEmpty()) {
                            selectContact(contactMatches[contactMatchIndex])
                        } else if (recipientController.hasContent()) {
                            editingRecipient = false
                            recipientController.stopCursorBlink()
                            inputController.startCursorBlink()
                            softLeftLabel.text = inputController.currentLabel()
                            composeText.post { composeText.requestFocus() }
                        }
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        // Mid-pick, Down shouldn't jump straight to the
                        // body and abandon the mark being highlighted.
                        if (recipientController.isPunctuationPickerActive()) {
                            recipientController.onKeyDown(event.keyCode, event)
                            return true
                        }
                        // Always just commits whatever's currently entered
                        // — a selected contact's address, or raw typed
                        // text — regardless of whether a dropdown is
                        // still showing.
                        recipientController.confirmPending()
                        if (recipientController.hasContent()) {
                            editingRecipient = false
                            recipientController.stopCursorBlink()
                            inputController.startCursorBlink()
                            softLeftLabel.text = inputController.currentLabel()
                            composeText.post { composeText.requestFocus() }
                        }
                        return true
                    }
                    // This phone's physical Clear key sends KEYCODE_BACK,
                    // not KEYCODE_DEL, so both are handled the same way.
                    KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_BACK -> {
                        if (recipientController.hasContent()) {
                            recipientController.onKeyDown(KeyEvent.KEYCODE_DEL, event)
                            updateContactMatches()
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
                        if (recipientController.onKeyDown(event.keyCode, event)) {
                            updateContactMatches()
                            return true
                        }
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
