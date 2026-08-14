package com.turbotext.app

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.KeyEvent
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File

/**
 * Two-stage compose: first the recipient number (numeric keypad, no T9
 * needed there), then the message body (full T9 + voice + photo). DPAD_CENTER
 * / the OK key moves from the "To" field to the body once a number is entered.
 */
class ComposeActivity : AppCompatActivity() {

    private lateinit var repo: SmsRepository
    private lateinit var engine: T9Engine
    private lateinit var inputController: T9InputController
    private lateinit var recipientController: T9InputController
    private lateinit var voiceHelper: GroqVoiceInputHelper
    private lateinit var btMicWarmup: BluetoothMicWarmup
    private lateinit var toText: EditText
    private lateinit var composeText: EditText
    private lateinit var modeIndicator: TextView
    private lateinit var suggestionsBar: TextView
    private lateinit var attachmentIndicator: TextView
    private lateinit var listeningIndicator: TextView

    private var editingRecipient = true
    private var recipientMode = InputMode.WORD
    private var pendingPhotoUri: Uri? = null
    private var pendingAudioUri: Uri? = null
    private var pendingVcardBytes: ByteArray? = null
    private var pendingVcardName: String = "contact.vcf"
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
        modeIndicator = findViewById(R.id.modeIndicator)
        suggestionsBar = findViewById(R.id.suggestionsBar)
        attachmentIndicator = findViewById(R.id.attachmentIndicator)
        listeningIndicator = findViewById(R.id.listeningIndicator)

        recipientController = T9InputController(
            engine = engine,
            outputView = toText,
            onModeChanged = { mode ->
                recipientMode = mode
                if (editingRecipient) modeIndicator.text = mode.label
            },
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
            learnsWords = false
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
            onModeChanged = { mode -> if (!editingRecipient) modeIndicator.text = mode.label },
            onSuggestionsChanged = { candidates, selected, windowSize -> renderSuggestions(candidates, selected, windowSize) }
        )
        if (!editingRecipient) inputController.startCursorBlink() else recipientController.startCursorBlink()
        modeIndicator.text = if (editingRecipient) recipientMode.label else InputMode.WORD.label

        // Forwarding a message launches here with these extras — recipient
        // still needs to be chosen, so this doesn't touch editingRecipient.
        intent?.getStringExtra("prefillText")?.let { inputController.setText(it) }
        intent?.getStringExtra("prefillImageUri")?.let {
            pendingPhotoUri = Uri.parse(it)
            onPhotoAttached()
        }

        voiceHelper = GroqVoiceInputHelper(this)
        voiceHelper.keepBluetoothRouteWarm = true
        btMicWarmup = BluetoothMicWarmup(this)
        audioMemoRecorder = AudioMemoRecorder(this)

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
     *  through to the recipientController's own native handling there. */
    private fun updateContactMatches() {
        if (recipientMode != InputMode.MULTITAP) {
            contactMatches = emptyList()
            renderContactMatches()
            return
        }
        val typed = recipientController.currentText()
        contactMatches = if (typed.isEmpty()) {
            emptyList()
        } else {
            val lower = typed.lowercase()
            allContacts.filter { contact ->
                contact.name.lowercase().split(Regex("\\s+")).any { it.startsWith(lower) } ||
                    contact.number.lowercase().startsWith(lower)
            }.take(10)
        }
        contactMatchIndex = 0
        renderContactMatches()
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
                        // Same refresh a manually-typed character would
                        // trigger — a dictated name/number should surface
                        // contact matches just like typing it would.
                        updateContactMatches()
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

    private fun showOptions() {
        if (editingRecipient) return
        val hasAttachment = pendingPhotoUri != null || pendingAudioUri != null || pendingVcardBytes != null
        val options = if (hasAttachment) {
            arrayOf("Take Photo", "Attach", "Remove Attachment", "Paste")
        } else {
            arrayOf("Take Photo", "Attach", "Paste")
        }
        AlertDialog.Builder(this)
            .setTitle("Options")
            .setItems(options) { _, which ->
                when (options[which]) {
                    "Take Photo" -> startPhotoCapture()
                    "Attach" -> showAttachMenu()
                    "Remove Attachment" -> {
                        pendingPhotoUri = null
                        pendingAudioUri = null
                        pendingVcardBytes = null
                        attachmentIndicator.visibility = View.GONE
                        Toast.makeText(this, "Attachment removed", Toast.LENGTH_SHORT).show()
                    }
                    "Paste" -> pasteFromClipboard()
                }
            }
            .show()
    }

    private fun showAttachMenu() {
        val options = arrayOf("Photo from Gallery", "Audio Recording", "Contact")
        AlertDialog.Builder(this)
            .setTitle("Attach")
            .setItems(options) { _, which ->
                when (options[which]) {
                    "Photo from Gallery" -> startGalleryPick()
                    "Audio Recording" -> startAudioMemoRecording()
                    "Contact" -> startContactPick()
                }
            }
            .show()
    }

    private fun startContactPick() {
        val intent = android.content.Intent(
            android.content.Intent.ACTION_PICK, android.provider.ContactsContract.Contacts.CONTENT_URI
        )
        startActivityForResult(intent, 403)
    }

    private fun loadVcardBytes(contactUri: Uri): Pair<ByteArray, String>? {
        return try {
            val cursor = contentResolver.query(
                contactUri,
                arrayOf(
                    android.provider.ContactsContract.Contacts.LOOKUP_KEY,
                    android.provider.ContactsContract.Contacts.DISPLAY_NAME
                ),
                null, null, null
            )
            cursor?.use {
                if (it.moveToFirst()) {
                    val lookupKey = it.getString(0)
                    val name = it.getString(1) ?: "contact"
                    val vcardUri = Uri.withAppendedPath(
                        android.provider.ContactsContract.Contacts.CONTENT_VCARD_URI, lookupKey
                    )
                    val bytes = contentResolver.openInputStream(vcardUri)?.use { stream -> stream.readBytes() }
                    if (bytes != null) {
                        val safeName = name.replace(Regex("[^A-Za-z0-9]"), "_") + ".vcf"
                        bytes to safeName
                    } else null
                } else null
            }
        } catch (e: Exception) {
            android.util.Log.w("TurboTextAttach", "failed to load vcard", e)
            null
        }
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
            pendingPhotoUri = null
            pendingVcardBytes = null
            pendingAudioUri = uri
            attachmentIndicator.text = "🎤 Audio attached"
            attachmentIndicator.visibility = View.VISIBLE
            Toast.makeText(this, "Audio attached — press Send", Toast.LENGTH_SHORT).show()
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

    private fun startPhotoCapture() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 300)
            return
        }
        val dir = File(cacheDir, "camera").apply { mkdirs() }
        val file = File(dir, "mms_${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        pendingPhotoUri = uri
        val captureIntent = android.content.Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, uri)
        }
        if (captureIntent.resolveActivity(packageManager) != null) {
            startActivityForResult(captureIntent, 400)
        } else {
            Toast.makeText(this, "No camera app available", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startGalleryPick() {
        val intent = android.content.Intent(android.content.Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"
        }
        startActivityForResult(android.content.Intent.createChooser(intent, "Choose Photo"), 401)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            400 -> {
                if (resultCode == RESULT_OK && pendingPhotoUri != null) {
                    onPhotoAttached()
                } else {
                    pendingPhotoUri = null
                }
            }
            401 -> {
                val uri = data?.data
                if (resultCode == RESULT_OK && uri != null) {
                    pendingPhotoUri = uri
                    onPhotoAttached()
                }
            }
            403 -> {
                val contactUri = data?.data
                if (resultCode == RESULT_OK && contactUri != null) {
                    Thread {
                        val vcard = loadVcardBytes(contactUri)
                        runOnUiThread {
                            if (isFinishing || isDestroyed) return@runOnUiThread
                            if (vcard != null) {
                                pendingPhotoUri = null
                                pendingAudioUri = null
                                pendingVcardBytes = vcard.first
                                pendingVcardName = vcard.second
                                attachmentIndicator.text = "👤 Contact attached"
                                attachmentIndicator.visibility = View.VISIBLE
                                Toast.makeText(this, "Contact attached — press Send", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(this, "Couldn't read that contact", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }.start()
                }
            }
        }
    }

    private fun onPhotoAttached() {
        pendingAudioUri = null
        pendingVcardBytes = null
        attachmentIndicator.text = "📷 Photo attached"
        attachmentIndicator.visibility = View.VISIBLE
        Toast.makeText(this, "Photo attached — press Send", Toast.LENGTH_SHORT).show()
    }

    private fun sendMessage() {
        recipientController.confirmPending()
        val address = recipientController.currentText().trim()
        val body = SettingsHelper.applySignature(this, inputController.currentText().trim())
        val photoUri = pendingPhotoUri
        val audioUri = pendingAudioUri
        val vcardBytes = pendingVcardBytes
        val vcardName = pendingVcardName

        if (address.isEmpty()) {
            Toast.makeText(this, "Enter a recipient first", Toast.LENGTH_SHORT).show()
            return
        }
        if (body.isEmpty() && photoUri == null && audioUri == null && vcardBytes == null) {
            Toast.makeText(this, "Nothing to send", Toast.LENGTH_SHORT).show()
            return
        }

        Thread {
            if (photoUri != null) {
                repo.sendMmsMessage(address, body, imageUri = photoUri)
            } else if (audioUri != null) {
                repo.sendMmsMessage(address, body, audioUri = audioUri)
            } else if (vcardBytes != null) {
                repo.sendMmsMessage(address, body, vcardBytes = vcardBytes, vcardName = vcardName)
            } else if (repo.isEmailAddress(address)) {
                // Plain SMS has no concept of an email recipient at all —
                // this has to go out as MMS, routed through the carrier's
                // MMSC. Whether that actually reaches the inbox depends on
                // the carrier supporting/enabling that bridge — not
                // something this app can guarantee.
                repo.sendMmsMessage(address, body)
            } else {
                repo.sendMessage(address, body)
            }
            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    Toast.makeText(this, "Sent", Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
        }.start()
    }

    override fun onResume() {
        super.onResume()
        // Start warming the Bluetooth mic route up as soon as this screen
        // is visible, same as ConversationActivity.
        btMicWarmup.poke()
    }

    override fun onPause() {
        super.onPause()
        // No point keeping the headset in call-mode for a screen that's no
        // longer on screen.
        btMicWarmup.coolDown()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            // Any key press counts as screen activity — pushes the
            // Bluetooth mic idle-teardown deadline back out. The mic button
            // itself uses extendIdle() rather than poke() — startVoiceRecording()
            // below makes its own Bluetooth-connect request for this exact
            // press, and a second concurrent one from poke() would race it
            // (see BluetoothMicWarmup.extendIdle's doc).
            if (event.keyCode in MicButtonKeyCodes.CODES) btMicWarmup.extendIdle() else btMicWarmup.poke()
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
                            modeIndicator.text = inputController.currentMode().label
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
                            modeIndicator.text = inputController.currentMode().label
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
                    modeIndicator.text = recipientMode.label
                }
                return true
            }
            // Up backs out to the "To:" field — but only when Up isn't
            // already spoken for browsing a Word-mode candidate list
            // (hasPendingWord()), which takes priority so it keeps working
            // exactly as before.
            if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP && !inputController.hasPendingWord()) {
                editingRecipient = true
                inputController.stopCursorBlink()
                recipientController.startCursorBlink()
                modeIndicator.text = recipientMode.label
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
