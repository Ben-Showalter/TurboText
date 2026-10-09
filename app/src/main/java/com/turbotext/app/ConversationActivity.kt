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
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class ConversationActivity : AppCompatActivity() {

    private lateinit var repo: SmsRepository
    private lateinit var engine: T9Engine
    private lateinit var inputController: T9InputController
    private lateinit var voiceHelper: GroqVoiceInputHelper
    private lateinit var messageAdapter: MessageAdapter
    private lateinit var messageList: RecyclerView
    private lateinit var composeText: EditText
    private lateinit var softLeftLabel: TextView
    private lateinit var suggestionsBar: TextView
    private lateinit var attachmentIndicator: TextView
    private lateinit var listeningIndicator: TextView

    private var threadId: Long = -1
    private var address: String = ""
    private lateinit var attachments: PendingAttachments
    private lateinit var picker: AttachmentPicker
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
        clearAlerts()
        // Alerts posted before they were keyed by thread used the address.
        // Clearing those once here stops one left over from the previous
        // version from sticking around forever.
        NotificationHelper.cancelForConversation(this, address)
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
        messageList.itemAnimator = null
        messageList.setItemViewCacheSize(6)
        messageAdapter = MessageAdapter(emptyList())
        messageList.adapter = messageAdapter
        messageList.addFocusScrollbar { messageAdapter.scrollbarPosition() }

        composeText = findViewById(R.id.composeText)
        composeText.movementMethod = android.text.method.ScrollingMovementMethod()
        composeText.setShowSoftInputOnFocus(false)
        composeText.hint = HintHelper.dictateHint(composeText)
        composeText.post { composeText.requestFocus() }
        softLeftLabel = findViewById(R.id.softLeftLabel)
        suggestionsBar = findViewById(R.id.suggestionsBar)
        attachmentIndicator = findViewById(R.id.attachmentIndicator)
        listeningIndicator = findViewById(R.id.listeningIndicator)

        inputController = T9InputController(
            engine = engine,
            outputView = composeText,
            onModeChanged = { label -> softLeftLabel.text = label },
            onSuggestionsChanged = { candidates, selected, windowSize -> renderSuggestions(candidates, selected, windowSize) },
            suggestionsBarView = suggestionsBar
        )
        inputController.startCursorBlink()
        // onModeChanged only fires on a change — without this the XML
        // layout's static placeholder text would show until the first
        // keypress, which no longer matches the real initial label now
        // that it's lowercase ("t9word") rather than the old fixed
        // "T9Word" the layout happened to hardcode.
        softLeftLabel.text = inputController.currentLabel()
        val draft = DraftHelper.getDraft(this, address)
        android.util.Log.i("TurboTextDraft", "onCreate restore: address=\"$address\" draft=${if (draft != null) "\"$draft\"" else "null"}")
        draft?.let { inputController.setText(it) }

        voiceHelper = GroqVoiceInputHelper(this)
        audioMemoRecorder = AudioMemoRecorder(this)
        attachments = PendingAttachments(this, attachmentIndicator)
        picker = AttachmentPicker(this) { attachments.add(it) }

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
        val cached = MessageCache.get(threadId)
        if (cached != null) {
            messageAdapter.update(cached, context = this)
            scrollToBottom()
            val newest = cached.maxByOrNull { it.date }
            android.util.Log.i("TurboTextPerf", "shown from cache instantly: ${System.currentTimeMillis() - openedAt}ms, ${cached.size} messages, newest hasImage=${newest?.imageUri != null}")
        }

        Thread {
            // Fast path: show the most recent messages immediately — but
            // only when nothing is on screen yet. If the cache was already
            // shown, swapping in a 12-message list and then the full list
            // again would remove and re-add the whole history twice.
            if (cached == null) {
                val recent = repo.getRecentMessages(threadId, 12)
                android.util.Log.i("TurboTextPerf", "fast query done: ${System.currentTimeMillis() - openedAt}ms, got ${recent.size} messages")
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    messageAdapter.update(recent)
                    scrollToBottom()
                }
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
                // Not browsing messages → this is a fresh open or a new
                // incoming message, so the newest message should be what's
                // on screen. Only when the user is stepping through history
                // (selection mode) do we respect their scroll position and
                // leave them where they are unless they were already at the
                // bottom.
                val keepAtBottom = selectedMessageIndex == null || !messageList.canScrollVertically(1)
                if (full == messageAdapter.currentItems()) return@runOnUiThread
                messageAdapter.update(full)
                if (keepAtBottom) scrollToBottom()
                android.util.Log.i("TurboTextPerf", "rendered: ${System.currentTimeMillis() - openedAt}ms")
            }
        }.start()
    }

    /** Pins the *bottom* of the newest message to the bottom of the list.
     *  scrollToPosition(last) alone doesn't do this when that message is
     *  taller than the screen — RecyclerView settles for bringing its top
     *  into view, which leaves the thread parked in the middle of a long
     *  final message with older messages showing above it. Re-running the
     *  scroll after layout, using the row's real measured height, lands its
     *  actual end at the bottom of the screen. */
    private fun scrollToBottom() {
        val lastIndex = messageAdapter.itemCount - 1
        if (lastIndex < 0) return
        messageList.scrollToPosition(lastIndex)
        messageList.post {
            if (isFinishing || isDestroyed) return@post
            val lm = messageList.layoutManager as? LinearLayoutManager ?: return@post
            val last = messageAdapter.itemCount - 1
            if (last < 0) return@post
            val child = lm.findViewByPosition(last)
            if (child == null) {
                lm.scrollToPositionWithOffset(last, 0)
            } else {
                val overhang = child.bottom - (messageList.height - messageList.paddingBottom)
                if (overhang > 0) messageList.scrollBy(0, overhang)
            }
        }
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

    /** Left softkey — jumps straight to a top-level typing mode instead of
     *  cycling '*' through it. Only wired up while actually composing (not
     *  in message-selection mode — see handleSelectionModeKey). */
    private fun showModeMenu() {
        val options = arrayOf("T9 Word", "ABC", "123")
        AlertDialog.Builder(this)
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

    private fun showMessageOptions(message: Message) {
        val options = mutableListOf("Copy Text to Clipboard", "Forward Message", "Translate to English")
        if (message.imageUri != null) options.add("Save to Gallery")
        if (message.videoUri != null) options.add("Play Video")
        if (message.videoUri != null || message.fileUri != null || message.audioUri != null) options.add("Save to Phone")
        if (message.fileUri != null) options.add("Open File")
        if (message.mmsDownloadPending) options.add("Download")
        if (message.vcardUri != null) options.add("Import Contact")
        if (message.audioUri != null) options.add("Play Audio")
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
                    "Play Audio" -> playVoiceMessage(message)
                    "Play Video" -> message.videoUri?.let { openMediaViewer(it, "video/*") }
                    "Save to Phone" -> saveAttachmentToPhone(message)
                    "Open File" -> openFile(message)
                    "Download" -> downloadPendingMms(message)
                    "View Details" -> showMessageDetails(message)
                    "Move to Trash" -> moveMessageToTrash(message)
                }
            }
            .show()
    }

    /** Copies the original pictures (all of them, for a multi-photo
     *  MMS) into Pictures/TurboText — no decode/re-encode, so full
     *  quality and no memory spike. */
    private fun saveImageToGallery(message: Message) {
        val uris = message.imageUris.map { Uri.parse(it) }
        if (uris.isEmpty()) return
        Thread {
            val saved = uris.count { MediaSaver.save(this, it, contentResolver.getType(it) ?: "image/jpeg") }
            runOnUiThread {
                val text = when {
                    saved == 0 -> "Couldn't save image"
                    uris.size == 1 -> "Saved to Gallery"
                    else -> "Saved $saved of ${uris.size} pictures to Gallery"
                }
                Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
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
            val (uri, mime) = when {
                message.imageUri != null -> message.imageUri to "image/jpeg"
                message.videoUri != null -> message.videoUri to "video/mp4"
                message.audioUri != null -> message.audioUri to "audio/mp4"
                message.vcardUri != null -> message.vcardUri to "text/x-vcard"
                message.fileUri != null -> message.fileUri to (message.fileMime ?: "application/octet-stream")
                else -> null to null
            }
            if (uri != null) {
                putExtra(ComposeActivity.EXTRA_PREFILL_ATTACHMENT_URI, uri)
                putExtra(ComposeActivity.EXTRA_PREFILL_ATTACHMENT_MIME, mime)
            }
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

        val lm = messageList.layoutManager as LinearLayoutManager

        if (targetIndex < 0 || targetIndex > maxIndex) {
            // No further message in this direction — but if the edge-most
            // message is taller than the screen and still clipped the way
            // we're trying to move, keep scrolling *within* it so its very
            // top (or bottom) can still be reached. This is what lets the
            // first line of a long opening message scroll fully into view.
            val view = lm.findViewByPosition(current) ?: return false
            if (direction < 0 && view.top < 0) {
                messageList.scrollBy(0, maxOf(direction * scrollStepPx(), view.top))
                return true
            }
            if (direction > 0 && view.bottom > messageList.height) {
                messageList.scrollBy(0, minOf(direction * scrollStepPx(), view.bottom - messageList.height))
                return true
            }
            return false
        }

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
        softLeftLabel.visibility = vis
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
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                activateSelectedMessage(index)
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

    /** Center-key ("OK") on a focused message: do the obvious thing for
     *  its type rather than making the user open the Options menu —
     *  play/stop a voice message, open a picture full screen, import a
     *  contact card. A plain text message falls back to the Options menu. */
    private fun activateSelectedMessage(adapterIndex: Int) {
        val realIndex = if (messageAdapter.hasLoadMoreRow()) adapterIndex - 1 else adapterIndex
        val message = messageAdapter.currentItems().getOrNull(realIndex) ?: return
        when {
            message.audioUri != null -> toggleVoiceMessage(message)
            message.imageUri != null -> openImages(message.imageUris)
            message.videoUri != null -> openMediaViewer(message.videoUri, "video/*")
            message.fileUri != null -> openFile(message)
            message.mmsDownloadPending -> downloadPendingMms(message)
            message.vcardUri != null -> importVcard(message)
            else -> showMessageOptions(message)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        picker.handleResult(requestCode, resultCode, data)
    }

    private fun sendCurrentMessage() {
        val typed = inputController.currentText().trim()
        val body = SettingsHelper.applySignature(this, typed)
        val toSend = attachments.items

        if (body.isEmpty() && toSend.isEmpty()) {
            Toast.makeText(this, "Nothing to send", Toast.LENGTH_SHORT).show()
            return
        }

        inputController.setText("")
        attachments.clear()
        DraftHelper.clearDraft(this, address)

        // A group thread's address is every member, comma-joined — the
        // reply goes to all of them as one group MMS.
        val recipients = address.split(",")
        if (toSend.isNotEmpty()) {
            listeningIndicator.text = if (toSend.any { it.isVideo }) "Compressing video…" else "Preparing attachment…"
            listeningIndicator.visibility = View.VISIBLE
        }
        Thread {
            val error = MessageSender.send(this, recipients, body, toSend) { status ->
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        listeningIndicator.text = status
                        listeningIndicator.visibility = View.VISIBLE
                    }
                }
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (toSend.isNotEmpty()) listeningIndicator.visibility = View.GONE
                if (error != null) {
                    // Nothing went out — put the message back so it isn't lost.
                    Toast.makeText(this, error, Toast.LENGTH_LONG).show()
                    if (inputController.currentText().isEmpty()) inputController.setText(typed)
                    attachments.restore(toSend)
                }
                loadMessages()
            }
        }.start()
    }

    // dispatchKeyEvent gets first crack at every physical key press, before
    // Android's normal focus system decides who handles it. That guarantees
    // typing/scrolling works no matter what view happens to have focus.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
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
                KeyEvent.KEYCODE_SOFT_LEFT -> {
                    showModeMenu()
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

    /** NotificationHelper.conversationKey for this thread. */
    private fun notificationKey() = NotificationHelper.conversationKey(threadId, address)

    /** Clears this conversation's notification, repeat alert and
     *  outer-screen card. The "couldn't be downloaded" notice goes too:
     *  opening a thread shows any "press OK to download" message there. */
    private fun clearAlerts() {
        NotificationHelper.cancelForConversation(this, notificationKey())
        SoundNotificationHelper.acknowledge(this, notificationKey())
        NotificationHelper.cancelMmsDownloadFailed(this)
    }

    private var messageObserver: android.database.ContentObserver? = null
    private val reloadHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val reloadRunnable = Runnable {
        loadMessages()
        SoundNotificationHelper.acknowledge(this, notificationKey())
    }

    override fun onResume() {
        super.onResume()
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
        stopVoiceMessagePlayback()
        NotificationHelper.cancelForConversation(this, notificationKey())
        messageObserver?.let { contentResolver.unregisterContentObserver(it) }
        reloadHandler.removeCallbacks(reloadRunnable)
        val text = inputController.currentText()
        android.util.Log.i("TurboTextDraft", "onPause save: address=\"$address\" text=\"$text\"")
        DraftHelper.saveDraft(this, address, text)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopVoiceMessagePlayback()
        voiceHelper.cancelRecording()
        audioMemoRecorder.cancelRecording()
        inputController.stopCursorBlink()
    }

    /** Plays back a received (or sent) voice-message MMS part straight from
     *  its content:// URI. Only one plays at a time; it's released on
     *  completion and whenever the thread leaves the screen. */
    private var voiceMessagePlayer: android.media.MediaPlayer? = null
    private var voiceMessagePlayingId: Long? = null

    /** Center-key action on a focused voice message: start it, or stop it
     *  if it's the one already playing. */
    private fun toggleVoiceMessage(message: Message) {
        if (voiceMessagePlayingId == message.id) {
            stopVoiceMessagePlayback()
        } else {
            playVoiceMessage(message)
        }
    }

    private fun playVoiceMessage(message: Message) {
        val uriString = message.audioUri ?: return
        stopVoiceMessagePlayback()
        try {
            voiceMessagePlayer = android.media.MediaPlayer().apply {
                setAudioStreamType(android.media.AudioManager.STREAM_MUSIC)
                setDataSource(this@ConversationActivity, Uri.parse(uriString))
                setOnCompletionListener { stopVoiceMessagePlayback() }
                setOnErrorListener { _, what, extra ->
                    android.util.Log.w("TurboTextAttach", "voice message playback error what=$what extra=$extra")
                    runOnUiThread { Toast.makeText(this@ConversationActivity, "Couldn't play voice message", Toast.LENGTH_SHORT).show() }
                    stopVoiceMessagePlayback()
                    true
                }
                prepare()
                start()
            }
            voiceMessagePlayingId = message.id
            messageAdapter.setPlayingAudioId(message.id)
        } catch (e: Exception) {
            android.util.Log.w("TurboTextAttach", "failed to play voice message", e)
            Toast.makeText(this, "Couldn't play voice message", Toast.LENGTH_SHORT).show()
            stopVoiceMessagePlayback()
        }
    }

    private fun stopVoiceMessagePlayback() {
        voiceMessagePlayer?.let {
            try {
                it.stop()
            } catch (_: Exception) {
            }
            it.release()
        }
        voiceMessagePlayer = null
        voiceMessagePlayingId = null
        messageAdapter.setPlayingAudioId(null)
    }

    private fun saveAttachmentToPhone(message: Message) {
        val (uri, mime) = when {
            message.videoUri != null -> message.videoUri to "video/mp4"
            message.fileUri != null -> message.fileUri to message.fileMime
            message.audioUri != null -> message.audioUri to null
            else -> return
        }
        Thread {
            val ok = MediaSaver.save(this, Uri.parse(uri), mime)
            runOnUiThread {
                Toast.makeText(this, if (ok) "Saved to phone" else "Couldn't save", Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    /** Asks the phone's MMS service to fetch a message we only have the
     *  carrier's notice for (an earlier download failed). When it lands,
     *  LibraryMmsReceivedReceiver saves it and the thread refreshes. */
    private fun downloadPendingMms(message: Message) {
        Thread {
            // Already downloaded (a leftover notice)? Just clear it.
            repo.removeStaleMmsNotices()
            if (!repo.mmsRowExists(message.id)) {
                runOnUiThread { if (!isFinishing && !isDestroyed) loadMessages() }
                return@Thread
            }
            val info = repo.mmsDownloadInfo(message.id)
            runOnUiThread {
                if (info == null) {
                    Toast.makeText(this, "Can't download this one — the carrier's link is missing", Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                try {
                    com.android.mms.transaction.DownloadManager.getInstance().downloadMultimediaMessage(
                        applicationContext, info.location, info.transactionId,
                        Uri.withAppendedPath(android.provider.Telephony.Mms.CONTENT_URI, message.id.toString()),
                        false, info.subId
                    )
                    Toast.makeText(this, "Downloading…", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    android.util.Log.w("TurboTextMms", "manual MMS download failed to start", e)
                    Toast.makeText(this, "Couldn't start the download", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    /** Hands a document attachment to whatever app on the phone opens
     *  that type (PDF viewer, etc.). */
    private fun openFile(message: Message) {
        val uri = message.fileUri ?: return
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse(uri), message.fileMime ?: "*/*")
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "No app on this phone opens ${message.fileMime ?: "this file"} — try Save to Phone", Toast.LENGTH_LONG).show()
        }
    }

    /** Center-key action on a focused picture/video message — opens it
     *  full screen (zoom or play with OK, save with the right softkey). */
    private fun openMediaViewer(uri: String, mime: String) {
        startActivity(
            android.content.Intent(this, MediaViewerActivity::class.java)
                .putExtra(MediaViewerActivity.EXTRA_URI, uri)
                .putExtra(MediaViewerActivity.EXTRA_MIME, mime)
        )
    }

    /** A message's pictures, full screen — Left/Right steps through them
     *  when there's more than one. */
    private fun openImages(uris: List<String>) {
        startActivity(
            android.content.Intent(this, MediaViewerActivity::class.java)
                .putExtra(MediaViewerActivity.EXTRA_URI, uris.first())
                .putExtra(MediaViewerActivity.EXTRA_MIME, "image/*")
                .putExtra(MediaViewerActivity.EXTRA_IMAGE_URIS, uris.toTypedArray())
        )
    }
}
