package com.turbotext.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView

class MainActivity : AppCompatActivity() {

    private lateinit var repo: SmsRepository
    private lateinit var adapter: ConversationAdapter
    private lateinit var list: RecyclerView
    private var focusedConversation: Conversation? = null

    // Keeps the list live while it's on screen — without this, a message
    // (or an MMS download finishing) after the list loaded only showed up
    // after leaving and coming back. Same debounced-observer approach as
    // ConversationActivity's thread view.
    private val reloadHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val reloadRunnable = Runnable {
        if (isFinishing || isDestroyed) return@Runnable
        refreshConversations()
        updateGroupsLabel()
    }
    private val messageObserver = object : android.database.ContentObserver(reloadHandler) {
        override fun onChange(selfChange: Boolean) {
            // One incoming message fires several notifications in a row
            // (row insert, then parts/thread updates) — reload once per
            // burst rather than once per notification.
            reloadHandler.removeCallbacks(reloadRunnable)
            reloadHandler.postDelayed(reloadRunnable, 800)
        }
    }
    private var observerRegistered = false

    // WRITE/READ_EXTERNAL_STORAGE are declared in the manifest with
    // maxSdkVersion="28" (scoped storage replaces them from API 29 on —
    // see MANAGE_EXTERNAL_STORAGE, requested separately via
    // NotificationSettingsActivity, for the one place this app still
    // needs broad storage access on newer versions). Above that level the
    // OS never grants them to the app at all, so checkSelfPermission
    // returns DENIED for these two forever — including them in the
    // unconditional list below made hasAllPermissions() permanently false
    // on API 29+ devices (confirmed on a Kyocera E4811/Android 10), which
    // meant refreshConversations() never ran and the conversation list
    // silently never populated, regardless of what's actually in SMS.
    private val requiredPermissions = arrayOf(
        Manifest.permission.READ_SMS,
        Manifest.permission.SEND_SMS,
        Manifest.permission.RECEIVE_SMS,
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.READ_PHONE_STATE
    ) + if (android.os.Build.VERSION.SDK_INT <= android.os.Build.VERSION_CODES.P) {
        arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE)
    } else {
        emptyArray()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        ThemeHelper.apply(this)
        repo = SmsRepository(this)

        list = findViewById(R.id.conversationList)
        list.setUpForDpad()
        // Without this, the RecyclerView container itself is a valid focus
        // target — descendantFocusability="afterDescendants" only affects
        // search order *within* it. Returning from a thread (window focus
        // restored before row 0 is bound) could land default focus on the
        // list container itself, which ConversationAdapter's `findFocus()
        // == null` check then reads as "something's already focused" and
        // skips handing focus to row 0 — visually the whole list gets the
        // default highlight instead of just the top conversation.
        list.isFocusable = false
        list.isFocusableInTouchMode = false
        adapter = ConversationAdapter(
            emptyList(),
            onSelected = { convo -> openThread(convo) },
            onFocusChanged = { convo -> focusedConversation = convo }
        )
        list.adapter = adapter

        findViewById<android.widget.TextView>(R.id.softLeftLabel).setOnClickListener { startNewMessage() }
        findViewById<android.widget.TextView>(R.id.softRightLabel).setOnClickListener { showOptions() }

        ensurePermissions()
    }

    override fun onResume() {
        super.onResume()
        // Retried every resume (not just first launch) so the Turbo Key
        // file appears on external storage as soon as "All files access"
        // is granted, without needing a reinstall.
        GroqConfig.ensureKeyFileExists()
        if (hasAllPermissions()) {
            refreshConversations()
            updateGroupsLabel()
            registerMessageObserver()
        }
    }

    override fun onPause() {
        super.onPause()
        if (observerRegistered) {
            contentResolver.unregisterContentObserver(messageObserver)
            observerRegistered = false
        }
        reloadHandler.removeCallbacks(reloadRunnable)
    }

    /** Watches all three URIs for the same reason ConversationActivity
     *  does — this device's provider can't be relied on to propagate
     *  changes up to the combined content://mms-sms/ authority alone. */
    private fun registerMessageObserver() {
        if (observerRegistered) return
        contentResolver.registerContentObserver(
            android.net.Uri.parse("content://mms-sms/"), true, messageObserver
        )
        contentResolver.registerContentObserver(
            android.provider.Telephony.Sms.CONTENT_URI, true, messageObserver
        )
        contentResolver.registerContentObserver(
            android.provider.Telephony.Mms.CONTENT_URI, true, messageObserver
        )
        observerRegistered = true
    }

    /** Highlights the "◀ Groups" header entry when a group thread has an
     *  unread message, so a new one landing on that separate screen is
     *  visible from the main list. Runs off the main thread — it's a
     *  couple of provider queries. */
    private fun updateGroupsLabel() {
        Thread {
            val unread = try {
                repo.hasUnreadGroupMessage()
            } catch (e: Exception) {
                android.util.Log.w("TurboTextGroup", "updateGroupsLabel: check failed", e)
                false
            }
            android.util.Log.i("TurboTextGroup", "updateGroupsLabel: unreadGroup=$unread")
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                val label = findViewById<android.widget.TextView>(R.id.groupsLabel)
                val theme = ThemeHelper.getCurrentTheme(this)
                // Stays the accent colour in both states — only the size,
                // weight and a trailing dot change. This device's system
                // font has no real bold face and Typeface.defaultFromStyle(
                // BOLD) returns one that merely *claims* bold, so
                // setTypeface alone is invisible — force the synthetic-bold
                // stroke directly.
                label.setTextColor(theme.accent)
                if (unread) {
                    label.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 17f)
                    label.setTypeface(null, android.graphics.Typeface.BOLD)
                    label.paint.isFakeBoldText = true
                    label.text = "◀ GROUPS ●"
                } else {
                    label.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
                    label.setTypeface(null, android.graphics.Typeface.NORMAL)
                    label.paint.isFakeBoldText = false
                    label.text = "◀ Groups"
                }
                label.invalidate()
            }
        }.start()
    }

    private fun hasAllPermissions() = requiredPermissions.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun ensurePermissions() {
        if (!hasAllPermissions()) {
            ActivityCompat.requestPermissions(this, requiredPermissions, 100)
        } else {
            continueSetup()
        }
        // No else-branch refresh call here — onResume() always fires
        // immediately after onCreate() regardless, and already handles
        // this case. Calling it here too meant two full, expensive
        // queries running concurrently on every single app launch,
        // contending with each other for the same database connection —
        // confirmed directly in a real log capture (both calls taking
        // 1000ms+ instead of the usual well under that).
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (hasAllPermissions()) {
            refreshConversations()
        }
        continueSetup()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == DefaultSmsRoleHelper.REQUEST_CODE) {
            refreshConversations()
            continueSetup()
        }
    }

    // Each setup prompt is shown at most once per launch; anything still
    // missing is asked for again on the next launch.
    private var askedDefaultThisLaunch = false
    private var askedAccessibilityThisLaunch = false

    /** Startup setup, one step at a time (each step's result calls this
     *  again): app permissions → default SMS app → accessibility. Steps
     *  already granted are skipped. */
    private fun continueSetup() {
        if (isFinishing || isDestroyed) return
        if (!DefaultSmsRoleHelper.isDefault(this) && !askedDefaultThisLaunch) {
            askedDefaultThisLaunch = true
            DefaultSmsRoleHelper.requestDefault(this)
            return
        }
        if (!AccessibilityHelper.isServiceEnabled(this) && !askedAccessibilityThisLaunch) {
            askedAccessibilityThisLaunch = true
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Turn on Accessibility for TurboText")
                .setMessage(
                    "Lets TurboText flash the outer-screen message icon when you press a key " +
                        "while you have unread texts. On the next screen, choose TurboText and turn it on."
                )
                .setPositiveButton("Open Settings") { _, _ -> AccessibilityHelper.openSettings(this) }
                .setNegativeButton("Not now", null)
                .show()
        }
    }

    private fun refreshConversations() {
        val startedAt = System.currentTimeMillis()
        android.util.Log.i("TurboTextPerf", "refreshConversations() started")

        // Show whatever's cached from a previous load immediately —
        // this is what actually fixes the repeated-slow-reopen problem,
        // since a real log showed the live query costing 2+ seconds
        // even 3.5 seconds after the app was last open. A live query
        // always still runs below to catch anything new since the
        // cache was built.
        val cachedList = ConversationListCache.get()
        if (cachedList != null) {
            adapter.update(cachedList, this)
            findViewById<android.view.View>(R.id.emptyView).visibility =
                if (cachedList.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
            android.util.Log.i("TurboTextPerf", "shown from cache instantly: ${System.currentTimeMillis() - startedAt}ms")
        }

        Thread {
            val prewarmed = ConversationPrewarm.consumeIfFresh()
            val conversations = if (prewarmed != null) {
                android.util.Log.i("TurboTextPerf", "used prewarmed conversations: ${System.currentTimeMillis() - startedAt}ms, got ${prewarmed.size}")
                prewarmed
            } else {
                val list = repo.getConversations()
                android.util.Log.i("TurboTextPerf", "getConversations() done: ${System.currentTimeMillis() - startedAt}ms, got ${list.size}")
                list
            }
            ConversationListCache.put(conversations)
            // Skip the redundant re-render (and the full rebind/refocus
            // that comes with it) when the live result is identical to
            // what the cache already showed — two back-to-back full
            // rebinds racing each other was implicated in a top-row
            // focus/scroll glitch.
            if (conversations == cachedList) {
                android.util.Log.i("TurboTextPerf", "live result matched cache, skipping re-render")
                return@Thread
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                adapter.update(conversations, this)
                findViewById<android.view.View>(R.id.emptyView).visibility =
                    if (conversations.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
                android.util.Log.i("TurboTextPerf", "refreshConversations() rendered: ${System.currentTimeMillis() - startedAt}ms")
            }
        }.start()
    }

    private fun openThread(convo: Conversation) {
        android.util.Log.i("TurboTextPerf", "conversation tapped, threadId=${convo.threadId}")
        val intent = Intent(this, ConversationActivity::class.java)
        intent.putExtra("threadId", convo.threadId)
        intent.putExtra("address", convo.address)
        intent.putExtra("displayName", convo.displayName)
        startActivity(intent)
    }

    private fun startNewMessage() {
        startActivity(Intent(this, ComposeActivity::class.java))
    }

    private fun showOptions() {
        // AlertDialog's list is D-pad navigable out of the box (arrow keys
        // move the highlight, center/OK selects) — no touch needed.
        val convo = focusedConversation
        val options = if (convo != null) {
            arrayOf("Move to Trash", "Archive All Conversations", "Add to Contacts", "Refresh", "Trash Bin", "Settings")
        } else {
            arrayOf("Archive All Conversations", "Refresh", "Trash Bin", "Settings")
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Options")
            .setItems(options) { _, which ->
                val choice = options[which]
                when (choice) {
                    "Move to Trash" -> convo?.let { trashConversation(it) }
                    "Archive All Conversations" -> archiveAllConversations()
                    "Add to Contacts" -> convo?.let { addToContacts(it) }
                    "Refresh" -> refreshConversations()
                    "Trash Bin" -> startActivity(Intent(this, TrashBinActivity::class.java))
                    "Settings" -> startActivity(Intent(this, SettingsActivity::class.java))
                }
            }
            .show()
    }

    private fun callFocusedConversation() {
        val convo = focusedConversation ?: return
        if (convo.address.contains(",")) {
            android.widget.Toast.makeText(this, "Can't call a group — no single number", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CALL_PHONE), 600)
            return
        }
        startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:${convo.address}")))
    }

    /** Hands off to Android's own "insert new contact" flow, pre-filled
     *  with the number — same pattern as vCard import, delegating to the
     *  system's own contact-editing UI rather than building our own. */
    private fun addToContacts(convo: Conversation) {
        if (convo.address.contains(",")) {
            android.widget.Toast.makeText(this, "Can't add a group to contacts", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(Intent.ACTION_INSERT, android.provider.ContactsContract.Contacts.CONTENT_URI)
        intent.putExtra(android.provider.ContactsContract.Intents.Insert.PHONE, convo.address)
        startActivity(intent)
    }

    private fun trashConversation(convo: Conversation) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Move to Trash?")
            .setMessage("This whole conversation with ${convo.displayName} will be moved to the trash.")
            .setPositiveButton("Move to Trash") { _, _ ->
                Thread {
                    val messages = repo.getMessages(convo.threadId)
                    messages.forEach { TrashHelper.trash(this, it) }
                    // Trashing without ever opening the conversation would
                    // otherwise leave it READ=0 in the provider forever,
                    // which keeps SmsRepository.hasAnyUnread() stuck true
                    // and the outer-screen pulse blinking on every key
                    // press system-wide indefinitely.
                    repo.markThreadRead(convo.threadId)
                    runOnUiThread {
                        android.widget.Toast.makeText(this, "Moved to trash", android.widget.Toast.LENGTH_SHORT).show()
                        refreshConversations()
                    }
                }.start()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Writes a plain-text transcript of every conversation into
     *  Internal Storage/Documents/Archived conversations/<today's date>/ —
     *  browsable in a normal file manager, not tucked away in this app's
     *  own sandboxed storage. Each run gets its own dated subfolder so
     *  archiving again later doesn't mix this batch's files in with an
     *  earlier one. */
    private fun archiveAllConversations() {
        Thread {
            try {
                val conversations = repo.getConversations()
                val documentsDir = android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOCUMENTS
                )
                val todayFolder = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                    .format(java.util.Date())
                val dir = java.io.File(java.io.File(documentsDir, "Archived conversations"), todayFolder)
                dir.mkdirs()
                for (convo in conversations) {
                    val messages = repo.getMessages(convo.threadId)
                    val safeName = convo.displayName.replace(Regex("[^A-Za-z0-9]"), "_")
                    val file = java.io.File(dir, "${safeName}_${System.currentTimeMillis()}.txt")
                    val sb = StringBuilder()
                    sb.append("Conversation with ${convo.displayName} (${convo.address})\n")
                    sb.append("Archived: ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date())}\n\n")
                    for (msg in messages) {
                        val who = if (msg.isOutgoing) "Me" else convo.displayName
                        val time = java.text.DateFormat.getDateTimeInstance(
                            java.text.DateFormat.SHORT, java.text.DateFormat.SHORT
                        ).format(java.util.Date(msg.date))
                        sb.append("[$time] $who: ${msg.body}\n")
                        if (msg.imageUri != null) sb.append("  [Picture attached — not included in this text archive]\n")
                    }
                    file.writeText(sb.toString())
                }
                runOnUiThread {
                    android.widget.Toast.makeText(this, "Archived ${conversations.size} conversations to ${dir.absolutePath}", android.widget.Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    android.widget.Toast.makeText(this, "Archive failed: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    // Physical softkeys on most feature-style Android phones map to these keycodes.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    startActivity(Intent(this, GroupMessagesActivity::class.java))
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    startActivity(Intent(this, BroadcastListsActivity::class.java))
                    return true
                }
                KeyEvent.KEYCODE_CALL -> {
                    callFocusedConversation()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_SOFT_RIGHT -> { showOptions(); return true }
            KeyEvent.KEYCODE_SOFT_LEFT -> { startNewMessage(); return true }
        }
        return super.onKeyDown(keyCode, event)
    }
}
