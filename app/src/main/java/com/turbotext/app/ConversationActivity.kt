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
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File

class ConversationActivity : AppCompatActivity() {

    private lateinit var repo: SmsRepository
    private lateinit var engine: T9Engine
    private lateinit var inputController: T9InputController
    private lateinit var voiceHelper: GroqVoiceInputHelper
    private lateinit var btMicWarmup: BluetoothMicWarmup
    private lateinit var messageAdapter: MessageAdapter
    private lateinit var messageList: RecyclerView
    private lateinit var composeText: EditText
    private lateinit var modeIndicator: TextView
    private lateinit var suggestionsBar: TextView
    private lateinit var attachmentIndicator: TextView
    private lateinit var listeningIndicator: TextView

    private var threadId: Long = -1
    private var address: String = ""
    private var pendingPhotoUri: Uri? = null
    private var pendingAudioUri: Uri? = null
    private var pendingVcardBytes: ByteArray? = null
    private var pendingVcardName: String = "contact.vcf"
    private lateinit var audioMemoRecorder: AudioMemoRecorder
    private var isRecordingMemo = false
    private var selectedMessageIndex: Int? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_conversation)

        ThemeHelper.apply(this)
        threadId = intent.getLongExtra("threadId", -1)
        address = intent.getStringExtra("address") ?: ""
        val displayName = intent.getStringExtra("displayName") ?: address
        NotificationHelper.cancelForAddress(this, address)
        SoundNotificationHelper.acknowledge(this, address)

        findViewById<TextView>(R.id.contactName).text = displayName
        repo = SmsRepository(this)
        engine = T9EngineHolder.get(this)

        messageList = findViewById(R.id.messageList)
        messageList.layoutManager = LinearLayoutManager(this).apply {
            // Without this, scrolling to the last message aligns its TOP
            // with the top of the screen — fine for short messages, but
            // for anything taller than the screen (a big photo, a long
            // text) that shows the wrong part of it, looking like the
            // thread jumped to the middle. Anchoring from the end means
            // the last message's actual bottom is what lands at the
            // bottom of the screen, regardless of its height.
            stackFromEnd = true
        }
        // Non-touch: this list is scrolled programmatically via D-pad, never
        // by taking focus, so it should never intercept key events itself.
        messageList.isFocusable = false
        messageList.isFocusableInTouchMode = false
        messageAdapter = MessageAdapter(emptyList())
        messageList.adapter = messageAdapter

        composeText = findViewById(R.id.composeText)
        composeText.movementMethod = android.text.method.ScrollingMovementMethod()
        composeText.setShowSoftInputOnFocus(false)
        composeText.hint = HintHelper.dictateHint(composeText)
        composeText.post { composeText.requestFocus() }
        modeIndicator = findViewById(R.id.modeIndicator)
        suggestionsBar = findViewById(R.id.suggestionsBar)
        attachmentIndicator = findViewById(R.id.attachmentIndicator)
        listeningIndicator = findViewById(R.id.listeningIndicator)

        inputController = T9InputController(
            engine = engine,
            outputView = composeText,
            onModeChanged = { mode -> modeIndicator.text = mode.label },
            onSuggestionsChanged = { candidates, selected, windowSize -> renderSuggestions(candidates, selected, windowSize) }
        )
        inputController.startCursorBlink()
        val draft = DraftHelper.getDraft(this, address)
        android.util.Log.i("TurboTextDraft", "onCreate restore: address=\"$address\" draft=${if (draft != null) "\"$draft\"" else "null"}")
        draft?.let { inputController.setText(it) }

        voiceHelper = GroqVoiceInputHelper(this)
        voiceHelper.keepBluetoothRouteWarm = true
        btMicWarmup = BluetoothMicWarmup(this)
        audioMemoRecorder = AudioMemoRecorder(this)

        // Not loaded here — onResume() always fires immediately after
        // onCreate() and handles the initial load itself, since it also
        // needs to reload on every later resume (see onResume() below).
        // Clear the cached list's unread flag immediately (cheap, in-memory)
        // so backing out to the conversation list reflects it right away
        // instead of waiting on a live re-query. The actual DB write still
        // happens off the main thread below.
        ConversationListCache.markThreadRead(threadId)
        Thread { repo.markThreadRead(threadId) }.start()
    }

    private fun renderSuggestions(candidates: List<String>, selected: Int, windowSize: Int = 5) {
        if (candidates.isEmpty()) {
            suggestionsBar.visibility = View.GONE
            return
        }
        suggestionsBar.visibility = View.VISIBLE
        suggestionsBar.text = SuggestionRenderer.build(candidates, selected, this, windowSize)
    }

    // All SMS/MMS content-provider access happens off the main thread —
    // these queries were the main cause of the app feeling slow/freezing,
    // especially switching between screens.

    private fun loadMessages() {
        val openedAt = System.currentTimeMillis()
        android.util.Log.i("TurboTextPerf", "loadMessages() started, threadId=$threadId")

        // Instant if we've already loaded this thread this session — no
        // query at all, just what's already in memory.
        MessageCache.get(threadId)?.let { cached ->
            messageAdapter.update(cached)
            messageList.scrollToPosition((cached.size - 1).coerceAtLeast(0))
            val newest = cached.maxByOrNull { it.date }
            android.util.Log.i("TurboTextPerf", "shown from cache instantly: ${System.currentTimeMillis() - openedAt}ms, ${cached.size} messages, newest hasImage=${newest?.imageUri != null}")
        }

        Thread {
            // Fast path: show the most recent messages immediately...
            val recent = repo.getRecentMessages(threadId, 12)
            android.util.Log.i("TurboTextPerf", "fast query done: ${System.currentTimeMillis() - openedAt}ms, got ${recent.size} messages")
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                messageAdapter.update(recent)
                messageList.scrollToPosition((recent.size - 1).coerceAtLeast(0))
            }

            // ...then quietly fill in the rest of the history behind it.
            // (Paginated "Load More" was tried here and pulled back —
            // this phone's SMS provider has a fixed connection cost per
            // query regardless of how much it returns, so loading in
            // smaller batches didn't meaningfully help and just added an
            // extra tap.)
            val full = repo.getMessages(threadId)
            android.util.Log.i("TurboTextPerf", "full query done: ${System.currentTimeMillis() - openedAt}ms, got ${full.size} messages")
            MessageCache.put(threadId, full)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                val wasAtBottom = !messageList.canScrollVertically(1)
                messageAdapter.update(full)
                if (wasAtBottom) messageList.scrollToPosition((full.size - 1).coerceAtLeast(0))
                android.util.Log.i("TurboTextPerf", "rendered: ${System.currentTimeMillis() - openedAt}ms")
            }
        }.start()
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

    /** Right softkey opens this instead of sending directly — Send now lives
     *  on the phone's dedicated call/send key, which is more standard.
     *  When a message is selected (via the Up/Down selection mode), this
     *  shows message-specific actions instead. */
    private fun showOptions() {
        val selected = selectedMessageIndex
        if (selected != null) {
            val realIndex = if (messageAdapter.hasLoadMoreRow()) selected - 1 else selected
            val message = messageAdapter.currentItems().getOrNull(realIndex) ?: return
            showMessageOptions(message)
            return
        }
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

    /** Exports a contact as a standard .vcf via Android's own vCard
     *  provider — the same mechanism the system Contacts app itself
     *  uses to share a contact. */
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

    private fun showMessageOptions(message: Message) {
        val options = mutableListOf("Copy Text to Clipboard", "Forward Message", "Translate to English")
        if (message.imageUri != null) options.add("Save to Gallery")
        if (message.vcardUri != null) options.add("Import Contact")
        options.add("View Details")
        options.add("Move to Trash")

        AlertDialog.Builder(this)
            .setTitle("Message Options")
            .setItems(options.toTypedArray()) { _, which ->
                when (options[which]) {
                    "Copy Text to Clipboard" -> copyMessageText(message)
                    "Forward Message" -> forwardMessage(message)
                    "Translate to English" -> translateMessage(message)
                    "Save to Gallery" -> saveImageToGallery(message)
                    "Import Contact" -> importVcard(message)
                    "View Details" -> showMessageDetails(message)
                    "Move to Trash" -> moveMessageToTrash(message)
                }
            }
            .show()
    }

    /** MediaStore.Images.Media.insertImage handles both the file write
     *  and registering it with the media scanner, so it shows up in the
     *  system Gallery app immediately rather than needing a rescan. */
    private fun saveImageToGallery(message: Message) {
        val uri = message.imageUri?.let { Uri.parse(it) } ?: return
        Thread {
            try {
                val bitmap = contentResolver.openInputStream(uri)?.use {
                    android.graphics.BitmapFactory.decodeStream(it)
                }
                if (bitmap == null) {
                    runOnUiThread { Toast.makeText(this, "Couldn't read image", Toast.LENGTH_SHORT).show() }
                    return@Thread
                }
                val savedUri = android.provider.MediaStore.Images.Media.insertImage(
                    contentResolver, bitmap, "TurboText_${System.currentTimeMillis()}", "Saved from TurboText"
                )
                runOnUiThread {
                    if (savedUri != null) {
                        Toast.makeText(this, "Saved to Gallery", Toast.LENGTH_SHORT).show()
                        try {
                            val viewIntent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                                setDataAndType(Uri.parse(savedUri), "image/*")
                                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            startActivity(viewIntent)
                        } catch (e: Exception) {
                            // No gallery app available to open it — the save
                            // itself already succeeded, so this is a soft
                            // failure, not worth bothering the user about.
                            android.util.Log.w("TurboTextAttach", "no app to view saved image", e)
                        }
                    } else {
                        Toast.makeText(this, "Couldn't save image", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("TurboTextAttach", "failed to save to gallery", e)
                runOnUiThread { Toast.makeText(this, "Save failed: ${e.message}", Toast.LENGTH_SHORT).show() }
            }
        }.start()
    }

    /** Hands off to Android's own contact-import flow rather than parsing
     *  and inserting the vCard's fields ourselves — the system already
     *  has a reliable, well-tested handler for this exact file type. */
    private fun importVcard(message: Message) {
        val uri = message.vcardUri?.let { android.net.Uri.parse(it) } ?: return
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "text/x-vcard")
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "No app available to import contacts", Toast.LENGTH_SHORT).show()
        }
    }

    private fun translateMessage(message: Message) {
        if (message.body.isBlank()) {
            Toast.makeText(this, "Nothing to translate", Toast.LENGTH_SHORT).show()
            return
        }
        val progress = AlertDialog.Builder(this)
            .setTitle("Translating…")
            .setMessage("Please wait")
            .setCancelable(false)
            .show()
        GroqTranslateHelper.translateToEnglish(
            this,
            message.body,
            onResult = { translated ->
                progress.dismiss()
                AlertDialog.Builder(this)
                    .setTitle("Translation")
                    .setMessage(translated)
                    .setPositiveButton("Copy") { _, _ ->
                        val clipboard = getSystemService(android.content.ClipboardManager::class.java)
                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("translation", translated))
                        Toast.makeText(this, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Close", null)
                    .show()
            },
            onError = { errorMsg ->
                progress.dismiss()
                Toast.makeText(this, errorMsg, Toast.LENGTH_LONG).show()
            }
        )
    }

    private fun copyMessageText(message: Message) {
        val clipboard = getSystemService(android.content.ClipboardManager::class.java)
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("message", message.body))
        Toast.makeText(this, "Copied to clipboard", Toast.LENGTH_SHORT).show()
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

    private fun forwardMessage(message: Message) {
        val intent = android.content.Intent(this, ComposeActivity::class.java).apply {
            if (message.body.isNotEmpty()) putExtra("prefillText", message.body)
            if (message.imageUri != null) putExtra("prefillImageUri", message.imageUri)
        }
        startActivity(intent)
    }

    private fun showMessageDetails(message: Message) {
        val dateStr = java.text.DateFormat.getDateTimeInstance().format(java.util.Date(message.date))
        val direction = if (message.isOutgoing) "Sent by you" else "Received"
        val type = if (message.isMms) "Picture message (MMS)" else "Text message (SMS)"
        // "Delivered time" isn't shown here — TurboText doesn't currently
        // track SMS delivery confirmation, so there's nothing real to show
        // for it yet.
        val details = "Type: $type\n$direction\nDate: $dateStr"
        AlertDialog.Builder(this)
            .setTitle("Message Details")
            .setMessage(details)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun moveMessageToTrash(message: Message) {
        AlertDialog.Builder(this)
            .setTitle("Move to Trash?")
            .setMessage("This message will be moved to the trash.")
            .setPositiveButton("Move to Trash") { _, _ ->
                TrashHelper.trash(this, message)
                exitSelectionMode()
                loadMessages()
                Toast.makeText(this, "Moved to trash", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** ~3/4 inch of scroll per D-pad press, used only as a fallback for
     *  messages taller than one inch (see below) — regardless of screen
     *  density. 0.75in = 120dp (since 1dp = 1/160in), converted to
     *  pixels here. */
    private fun scrollStepPx(): Int = (120 * resources.displayMetrics.density).toInt()

    private fun oneInchPx(): Int = (160 * resources.displayMetrics.density).toInt()

    /** After a ¾" scroll step (used for tall messages, below), whatever
     *  message lands at/nearest the vertical center becomes selected —
     *  clamped to move at most one message per press, so a cluster of
     *  short messages doesn't get skipped over within a single step. */
    private fun updateSelectionToMiddleVisible() {
        if (messageAdapter.hasLoadMoreRow() && !messageList.canScrollVertically(-1)) {
            selectedMessageIndex = 0
            messageAdapter.setSelectedPosition(0)
            return
        }
        val viewportCenter = messageList.height / 2
        var closestPosition: Int? = null
        var closestDistance = Int.MAX_VALUE
        for (i in 0 until messageList.childCount) {
            val child = messageList.getChildAt(i) ?: continue
            val childCenter = (child.top + child.bottom) / 2
            val distance = kotlin.math.abs(childCenter - viewportCenter)
            if (distance < closestDistance) {
                closestDistance = distance
                closestPosition = messageList.getChildAdapterPosition(child)
            }
        }
        val naturalPos = closestPosition
        if (naturalPos != null && naturalPos != RecyclerView.NO_POSITION) {
            val current = selectedMessageIndex ?: naturalPos
            val pos = when {
                naturalPos > current -> (current + 1).coerceAtMost(naturalPos)
                naturalPos < current -> (current - 1).coerceAtLeast(naturalPos)
                else -> naturalPos
            }
            selectedMessageIndex = pos
            messageAdapter.setSelectedPosition(pos)
        }
    }

    /** Moves selection exactly one message in [direction] (-1 = up/older,
     *  +1 = down/newer) — guaranteed, no skipping. If that next message
     *  is a normal size (one inch or shorter), it jumps straight to
     *  showing it fully in one step. Only a message taller than that
     *  falls back to the old ¾"-at-a-time scroll, so reading through a
     *  long message still takes several presses instead of jumping
     *  straight past it. Returns false if there's nowhere further to go
     *  in that direction. */
    private fun stepSelection(direction: Int): Boolean {
        val current = selectedMessageIndex ?: return false
        val maxIndex = messageAdapter.itemCount - 1
        val targetIndex = current + direction
        if (targetIndex < 0 || targetIndex > maxIndex) return false

        val lm = messageList.layoutManager as LinearLayoutManager
        val targetView = lm.findViewByPosition(targetIndex)

        if (targetView != null && targetView.height <= oneInchPx()) {
            selectedMessageIndex = targetIndex
            messageAdapter.setSelectedPosition(targetIndex)
            messageList.scrollToPosition(targetIndex)
        } else {
            // Tall message (or not yet measured, which we treat the same
            // way defensively) — scroll toward it gradually instead of
            // jumping, same as before.
            messageList.scrollBy(0, direction * scrollStepPx())
            updateSelectionToMiddleVisible()
        }
        return true
    }

    private fun exitSelectionMode() {
        selectedMessageIndex = null
        messageAdapter.setSelectedPosition(null)
        inputController.startCursorBlink()
        setComposeAreaVisible(true)
        composeText.post { composeText.requestFocus() }
    }

    /** Collapsing the compose box while browsing frees up real screen
     *  space for the message list — this phone's screen is small enough
     *  that the compose box was hiding some shorter messages entirely.
     *  suggestionsBar/attachmentIndicator/listeningIndicator aren't
     *  touched here since they already have their own state-driven
     *  visibility that will resolve correctly once compose mode returns. */
    private fun setComposeAreaVisible(visible: Boolean) {
        val vis = if (visible) View.VISIBLE else View.GONE
        composeText.visibility = vis
        modeIndicator.visibility = vis
    }

    /** While a message (or the Load More row) is selected, only these
     *  specific keys do anything — everything else is swallowed so a
     *  stray keypress can't accidentally start typing into the compose
     *  box while something is visibly selected. */
    private fun handleSelectionModeKey(event: KeyEvent): Boolean {
        val index = selectedMessageIndex ?: return true
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                stepSelection(-1)
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (!stepSelection(1)) exitSelectionMode()
                return true
            }
            KeyEvent.KEYCODE_SOFT_RIGHT -> {
                showOptions()
                return true
            }
            KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_BACK -> {
                finish()
                return true
            }
            else -> return true
        }
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
        val intent = android.content.Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, uri)
        }
        if (intent.resolveActivity(packageManager) != null) {
            startActivityForResult(intent, 400)
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

    private fun sendCurrentMessage() {
        val body = SettingsHelper.applySignature(this, inputController.currentText().trim())
        val photoUri = pendingPhotoUri
        val audioUri = pendingAudioUri
        val vcardBytes = pendingVcardBytes
        val vcardName = pendingVcardName

        if (body.isEmpty() && photoUri == null && audioUri == null && vcardBytes == null) {
            Toast.makeText(this, "Nothing to send", Toast.LENGTH_SHORT).show()
            return
        }

        inputController.setText("")
        pendingPhotoUri = null
        pendingAudioUri = null
        pendingVcardBytes = null
        attachmentIndicator.visibility = View.GONE
        DraftHelper.clearDraft(this, address)

        Thread {
            if (address.contains(",")) {
                // A group thread — reply goes to everyone, not just one
                // address. Same experimental caveat as the rest of group
                // messaging (see SmsRepository.sendGroupMmsMessage).
                repo.sendGroupMmsMessage(address.split(","), body)
            } else if (photoUri != null) {
                repo.sendMmsMessage(address, body, imageUri = photoUri)
            } else if (audioUri != null) {
                repo.sendMmsMessage(address, body, audioUri = audioUri)
            } else if (vcardBytes != null) {
                repo.sendMmsMessage(address, body, vcardBytes = vcardBytes, vcardName = vcardName)
            } else if (repo.isEmailAddress(address)) {
                repo.sendMmsMessage(address, body)
            } else {
                repo.sendMessage(address, body)
            }
            runOnUiThread {
                if (!isFinishing && !isDestroyed) loadMessages()
            }
        }.start()
    }

    // dispatchKeyEvent gets first crack at every physical key press, before
    // Android's normal focus system decides who handles it. That guarantees
    // typing/scrolling works no matter what view happens to have focus.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            // Any key press counts as screen activity — pushes the
            // Bluetooth mic idle-teardown deadline back out. This also
            // covers a long hold of the mic button itself: key-repeat
            // events keep arriving while it's held (see the repeatCount
            // handling below), so a recording longer than the idle timeout
            // won't get the route pulled out from under it. The mic button
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
            if (selectedMessageIndex != null) {
                return handleSelectionModeKey(event)
            }
            when (event.keyCode) {
                // Standard "send" key on basic-phone keypads.
                KeyEvent.KEYCODE_CALL -> {
                    sendCurrentMessage()
                    return true
                }
                KeyEvent.KEYCODE_SOFT_RIGHT -> {
                    showOptions()
                    return true
                }
                in MicButtonKeyCodes.CODES -> {
                    // Hold to record, release to send — key-repeat events
                    // (repeatCount > 0) fire while held, so only react to
                    // the initial press.
                    if (event.repeatCount == 0) startVoiceRecording()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_UP -> {
                    // While a word is mid-composition, Up/Down move the
                    // suggestion highlight. Otherwise Up moves the cursor
                    // up a line within whatever's typed so far — and only
                    // once there's no line above to go to does it exit the
                    // compose box into message selection, landing directly
                    // on the newest message (the one right above the
                    // compose box, likely already visible once it
                    // collapses).
                    if (inputController.hasPendingWord()) {
                        inputController.onKeyDown(event.keyCode, event)
                    } else if (!inputController.moveCursorLineUp()) {
                        if (messageAdapter.itemCount > 0) {
                            inputController.stopCursorBlink()
                            setComposeAreaVisible(false)
                            val lastIndex = messageAdapter.itemCount - 1
                            selectedMessageIndex = lastIndex
                            messageAdapter.setSelectedPosition(lastIndex)
                            messageList.scrollToPosition(lastIndex)
                        }
                    }
                    return true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (inputController.hasPendingWord()) {
                        inputController.onKeyDown(event.keyCode, event)
                    } else {
                        inputController.moveCursorLineDown()
                    }
                    return true
                }
                KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_BACK -> {
                    // Backing the cursor all the way to the start and
                    // pressing Clear again exits (saving whatever's typed
                    // as a draft, via onPause) — you don't have to delete
                    // everything character by character first. Handling
                    // BACK the same as DEL here because on this phone the
                    // physical Clear key sends KEYCODE_BACK, not KEYCODE_DEL.
                    if (inputController.isCursorAtStart()) {
                        finish()
                    } else {
                        inputController.onKeyDown(KeyEvent.KEYCODE_DEL, event)
                    }
                    return true
                }
                else -> {
                    if (inputController.onKeyDown(event.keyCode, event)) return true
                    // Diagnostic: tells us the real keycode of any button
                    // that isn't wired up yet (useful for hardware quirks
                    // like camera/send keys that vary by device).
                    Toast.makeText(this, "Unhandled key: ${KeyEvent.keyCodeToString(event.keyCode)}", Toast.LENGTH_SHORT).show()
                }
            }
        } else if (event.action == KeyEvent.ACTION_UP) {
            if (event.keyCode in MicButtonKeyCodes.CODES) {
                stopVoiceRecordingAndSend()
                return true
            }
            if (inputController.onKeyUp(event.keyCode)) return true
        }
        return super.dispatchKeyEvent(event)
    }

    private var messageObserver: android.database.ContentObserver? = null
    private val reloadHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val reloadRunnable = Runnable {
        loadMessages()
        SoundNotificationHelper.acknowledge(this, address)
    }

    override fun onResume() {
        super.onResume()
        // Start warming the Bluetooth mic route up as soon as the thread is
        // visible — covers both the initial open and returning to an
        // already-open conversation from the background.
        btMicWarmup.poke()
        // Covers both the initial open (onResume always follows onCreate)
        // and returning to an already-open conversation from the
        // background — the ContentObserver below only catches changes
        // that land while it's registered, i.e. while resumed, so a
        // message that arrived while this screen was paused would
        // otherwise sit unseen until some later change happened to fire
        // it. Re-querying here closes that gap the same way MainActivity's
        // onResume already does for the conversation list.
        loadMessages()
        if (messageObserver == null) {
            messageObserver = object : android.database.ContentObserver(
                android.os.Handler(android.os.Looper.getMainLooper())
            ) {
                override fun onChange(selfChange: Boolean) {
                    super.onChange(selfChange)
                    android.util.Log.i("TurboTextLive", "ContentObserver fired, threadId=$threadId")
                    // A single incoming message can trigger several
                    // separate change notifications in quick succession
                    // (the notification row, then the actual content) —
                    // debounced so that only reloads once per burst.
                    reloadHandler.removeCallbacks(reloadRunnable)
                    reloadHandler.postDelayed(reloadRunnable, 500)
                }
            }
        }
        // Registered on all three — the combined content://mms-sms/
        // authority is what AOSP's own messaging apps watch, but this
        // device's provider has repeatedly turned out to skip standard
        // behaviors elsewhere in this project, so the specific
        // content://sms/ and content://mms/ URIs are also covered as a
        // hedge in case notifications only propagate at that level here.
        contentResolver.registerContentObserver(
            android.net.Uri.parse("content://mms-sms/"), true, messageObserver!!
        )
        contentResolver.registerContentObserver(
            android.provider.Telephony.Sms.CONTENT_URI, true, messageObserver!!
        )
        contentResolver.registerContentObserver(
            android.provider.Telephony.Mms.CONTENT_URI, true, messageObserver!!
        )
    }

    override fun onPause() {
        super.onPause()
        // No point keeping the headset in call-mode for a thread that's no
        // longer on screen.
        btMicWarmup.coolDown()
        NotificationHelper.cancelForAddress(this, address)
        messageObserver?.let { contentResolver.unregisterContentObserver(it) }
        reloadHandler.removeCallbacks(reloadRunnable)
        val text = inputController.currentText()
        android.util.Log.i("TurboTextDraft", "onPause save: address=\"$address\" text=\"$text\"")
        DraftHelper.saveDraft(this, address, text)
    }

    override fun onDestroy() {
        super.onDestroy()
        voiceHelper.cancelRecording()
        audioMemoRecorder.cancelRecording()
        inputController.stopCursorBlink()
    }
}
