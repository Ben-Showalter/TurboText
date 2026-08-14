package com.turbotext.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class GroupMessagesActivity : AppCompatActivity() {

    private lateinit var repo: SmsRepository
    private lateinit var adapter: ConversationAdapter
    private lateinit var list: RecyclerView
    private lateinit var emptyView: TextView
    private val pendingAddresses = mutableListOf<String>()
    private var focusedGroup: Conversation? = null
    /** Set while picking a contact specifically to add to an existing
     *  group, as opposed to the new-group flow — both reuse the same
     *  contact-picker request code path. */
    private var addingToGroup: Conversation? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_group_messages)

        ThemeHelper.apply(this)
        repo = SmsRepository(this)
        list = findViewById(R.id.groupList)
        emptyView = findViewById(R.id.groupEmptyView)
        list.layoutManager = LinearLayoutManager(this)
        list.snapTopRowOnIdle()
        adapter = ConversationAdapter(
            emptyList(),
            onSelected = { convo ->
                val intent = Intent(this, ConversationActivity::class.java)
                intent.putExtra("threadId", convo.threadId)
                intent.putExtra("address", convo.address) // comma-joined for group threads
                intent.putExtra("displayName", convo.displayName)
                startActivity(intent)
            },
            onFocusChanged = { convo -> focusedGroup = convo }
        )
        list.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        val cached = GroupThreadsCache.get()
        if (cached != null) {
            adapter.update(cached)
            emptyView.visibility = if (cached.isEmpty()) View.VISIBLE else View.GONE
        }
        Thread {
            val groups = repo.getGroupThreads()
            GroupThreadsCache.put(groups)
            // Skip the redundant re-render (and the full rebind/refocus
            // that comes with it) when nothing actually changed since the
            // cache was shown — the same fix applied to the main
            // conversation list, since this screen has its own separate
            // cache-then-live flow that never got the same treatment.
            if (groups == cached) return@Thread
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                adapter.update(groups)
                emptyView.visibility = if (groups.isEmpty()) View.VISIBLE else View.GONE
            }
        }.start()
    }

    private fun startNewGroup() {
        pendingAddresses.clear()
        pickNextContact()
    }

    private fun pickNextContact() {
        val intent = Intent(Intent.ACTION_PICK, android.provider.ContactsContract.Contacts.CONTENT_URI)
        startActivityForResult(intent, 510)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 510) {
            val contactUri = data?.data
            if (resultCode == Activity.RESULT_OK && contactUri != null) {
                Thread {
                    val number = loadNumber(contactUri)
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        if (number != null && !pendingAddresses.contains(number)) {
                            pendingAddresses.add(number)
                        }
                        promptAddAnother()
                    }
                }.start()
            } else if (pendingAddresses.isNotEmpty()) {
                promptAddAnother()
            }
        } else if (requestCode == 511 && resultCode == Activity.RESULT_OK) {
            val body = data?.getStringExtra(TextEntryActivity.EXTRA_RESULT_TEXT)?.trim()
            if (!body.isNullOrEmpty() && pendingAddresses.size >= 2) {
                sendNewGroup(body)
            }
        } else if (requestCode == 512 && resultCode == Activity.RESULT_OK) {
            val nickname = data?.getStringExtra(TextEntryActivity.EXTRA_RESULT_TEXT)?.trim()
            val group = focusedGroup
            if (group != null) {
                if (nickname.isNullOrEmpty()) {
                    GroupNicknameHelper.clearNickname(this, group.threadId)
                } else {
                    GroupNicknameHelper.setNickname(this, group.threadId, nickname)
                }
                Thread {
                    val groups = repo.getGroupThreads()
                    runOnUiThread {
                        if (!isFinishing && !isDestroyed) adapter.update(groups)
                    }
                }.start()
            }
        } else if (requestCode == 513) {
            val contactUri = data?.data
            val group = addingToGroup
            addingToGroup = null
            if (resultCode == Activity.RESULT_OK && contactUri != null && group != null) {
                Thread {
                    val number = loadNumber(contactUri)
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        if (number == null) {
                            Toast.makeText(this, "Couldn't read that contact", Toast.LENGTH_SHORT).show()
                            return@runOnUiThread
                        }
                        val existing = group.address.split(",").filter { it.isNotBlank() }
                        if (existing.contains(number)) {
                            Toast.makeText(this, "Already in this group", Toast.LENGTH_SHORT).show()
                            return@runOnUiThread
                        }
                        val expanded = existing + number
                        // Group MMS threads are keyed by their exact
                        // participant set — there's no way to "add
                        // someone" to the existing thread's history, only
                        // to start using an expanded participant list
                        // going forward, which becomes its own thread
                        // (an existing one, if this exact expanded group
                        // has messaged before).
                        Toast.makeText(this, "Compose a message to start the expanded group", Toast.LENGTH_LONG).show()
                        pendingAddresses.clear()
                        pendingAddresses.addAll(expanded)
                        val intent = Intent(this, TextEntryActivity::class.java)
                        intent.putExtra(TextEntryActivity.EXTRA_TITLE, "Message to ${expanded.size} people")
                        intent.putExtra(TextEntryActivity.EXTRA_SEND_MODE, true)
                        startActivityForResult(intent, 511)
                    }
                }.start()
            }
        }
    }

    private fun promptAddAnother() {
        if (pendingAddresses.size < 2) {
            Toast.makeText(this, "Added — a group needs at least 2 people, pick another", Toast.LENGTH_SHORT).show()
            pickNextContact()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("${pendingAddresses.size} people added")
            .setMessage("Add another person, or start composing the message?")
            .setPositiveButton("Add Another") { _, _ -> pickNextContact() }
            .setNegativeButton("Compose Message") { _, _ ->
                val intent = Intent(this, TextEntryActivity::class.java)
                intent.putExtra(TextEntryActivity.EXTRA_TITLE, "Message to ${pendingAddresses.size} people")
                intent.putExtra(TextEntryActivity.EXTRA_SEND_MODE, true)
                startActivityForResult(intent, 511)
            }
            .show()
    }

    private fun loadNumber(contactUri: Uri): String? {
        return try {
            val cursor = contentResolver.query(
                contactUri, arrayOf(android.provider.ContactsContract.Contacts._ID), null, null, null
            )
            cursor?.use {
                if (!it.moveToFirst()) return null
                val contactId = it.getString(0) ?: return null
                val phoneCursor = contentResolver.query(
                    android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    arrayOf(android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER),
                    "${android.provider.ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
                    arrayOf(contactId), null
                )
                phoneCursor?.use { pc -> if (pc.moveToFirst()) pc.getString(0) else null }
            }
        } catch (e: Exception) {
            null
        }
    }

    /** This is the single most uncertain part of this whole feature —
     *  see SmsRepository.sendGroupMmsMessage's own note. If this doesn't
     *  work as-is, it's the specific place to focus on fixing, likely
     *  with real error feedback the same way the earlier audio/vcard
     *  attachment work needed. */
    private fun sendNewGroup(body: String) {
        val addresses = pendingAddresses.toList()
        Toast.makeText(this, "Sending group message…", Toast.LENGTH_SHORT).show()
        Thread {
            try {
                repo.sendGroupMmsMessage(addresses, body)
            } catch (e: Exception) {
                android.util.Log.e("TurboTextGroup", "group send failed", e)
            }
            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    Toast.makeText(this, "Sent", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_RIGHT -> { finish(); return true }
                KeyEvent.KEYCODE_SOFT_LEFT -> { startNewGroup(); return true }
                KeyEvent.KEYCODE_SOFT_RIGHT -> { showOptions(); return true }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun showOptions() {
        val group = focusedGroup
        val options = if (group != null) {
            arrayOf("Nickname Group", "Add Person to Group", "Archive All Groups", "Move to Trash")
        } else {
            arrayOf("New Group Message", "Archive All Groups")
        }
        AlertDialog.Builder(this)
            .setTitle("Options")
            .setItems(options) { _, which ->
                when (options[which]) {
                    "Nickname Group" -> group?.let { promptNickname(it) }
                    "Add Person to Group" -> group?.let { addPersonToGroup(it) }
                    "Archive All Groups" -> archiveAllGroups()
                    "Move to Trash" -> group?.let { trashGroup(it) }
                    "New Group Message" -> startNewGroup()
                }
            }
            .show()
    }

    private fun trashGroup(group: Conversation) {
        AlertDialog.Builder(this)
            .setTitle("Move to Trash?")
            .setMessage("This whole group conversation will be moved to the trash.")
            .setPositiveButton("Move to Trash") { _, _ -> doTrashGroup(group) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun doTrashGroup(group: Conversation) {
        Thread {
            val messages = repo.getMessages(group.threadId)
            messages.forEach { TrashHelper.trash(this, it) }
            // See MainActivity.trashConversation for why this matters:
            // without it, an unread thread trashed here without ever being
            // opened stays READ=0 forever and keeps the outer-screen pulse
            // blinking on every key press system-wide indefinitely.
            repo.markThreadRead(group.threadId)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                Toast.makeText(this, "Moved to trash", Toast.LENGTH_SHORT).show()
                Thread {
                    val groups = repo.getGroupThreads()
                    runOnUiThread { if (!isFinishing && !isDestroyed) adapter.update(groups) }
                }.start()
            }
        }.start()
    }

    /** Same mechanism as the main conversation list's archive-all — one
     *  combined plain-text file covering every group conversation, in
     *  Internal Storage/Documents/Archived conversations/, browsable
     *  outside the app. */
    private fun archiveAllGroups() {
        Thread {
            try {
                val groups = repo.getGroupThreads()
                val documentsDir = android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOCUMENTS
                )
                val dir = java.io.File(documentsDir, "Archived conversations")
                dir.mkdirs()
                for (group in groups) {
                    val messages = repo.getMessages(group.threadId)
                    val safeName = group.displayName.replace(Regex("[^A-Za-z0-9]"), "_")
                    val file = java.io.File(dir, "${safeName}_${System.currentTimeMillis()}.txt")
                    val sb = StringBuilder()
                    sb.append("Group conversation: ${group.displayName} (${group.address})\n")
                    sb.append("Archived: ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date())}\n\n")
                    for (msg in messages) {
                        val who = if (msg.isOutgoing) "Me" else (msg.senderName ?: group.displayName)
                        val time = java.text.DateFormat.getDateTimeInstance(
                            java.text.DateFormat.SHORT, java.text.DateFormat.SHORT
                        ).format(java.util.Date(msg.date))
                        sb.append("[$time] $who: ${msg.body}\n")
                        if (msg.imageUri != null) sb.append("  [Picture attached — not included in this text archive]\n")
                    }
                    file.writeText(sb.toString())
                }
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        Toast.makeText(this, "Archived ${groups.size} groups to ${dir.absolutePath}", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        Toast.makeText(this, "Archive failed: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }.start()
    }

    private fun promptNickname(group: Conversation) {
        val intent = Intent(this, TextEntryActivity::class.java)
        intent.putExtra(TextEntryActivity.EXTRA_TITLE, "Nickname This Group")
        intent.putExtra(TextEntryActivity.EXTRA_INITIAL_TEXT, GroupNicknameHelper.getNickname(this, group.threadId) ?: "")
        intent.putExtra(TextEntryActivity.EXTRA_ALLOW_VOICE, false)
        startActivityForResult(intent, 512)
    }

    private fun addPersonToGroup(group: Conversation) {
        addingToGroup = group
        val intent = Intent(Intent.ACTION_PICK, android.provider.ContactsContract.Contacts.CONTENT_URI)
        startActivityForResult(intent, 513)
    }
}
