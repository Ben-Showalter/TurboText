package com.turbotext.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class BroadcastListDetailActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_CSV = 504
        /** A CSV import's summary to show on opening (a new list from CSV). */
        const val EXTRA_IMPORT_SUMMARY = "import_summary"
    }

    private lateinit var listId: String
    private lateinit var adapter: BroadcastListDetailAdapter
    private lateinit var recyclerView: RecyclerView
    private lateinit var repo: SmsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_broadcast_list_detail)

        ThemeHelper.apply(this)
        listId = intent.getStringExtra("list_id") ?: run { finish(); return }
        repo = SmsRepository(this)

        recyclerView = findViewById(R.id.detailList)
        recyclerView.layoutManager = LinearLayoutManager(this)
        adapter = BroadcastListDetailAdapter(
            emptyList(),
            onCompose = { promptComposeAndSend() },
            onAddContact = { startContactPick() },
            onSelectMember = { contact -> confirmRemoveMember(contact) }
        )
        recyclerView.adapter = adapter
        if (savedInstanceState == null) {
            intent.getStringExtra(EXTRA_IMPORT_SUMMARY)?.let { CsvImport.showSummary(this, it) }
        }
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun reload() {
        val list = BroadcastListHelper.getList(this, listId)
        if (list == null) {
            finish()
            return
        }
        findViewById<TextView>(R.id.detailTitle).text = list.name
        adapter.update(list.contacts)
    }

    private fun startContactPick() {
        val intent = Intent(Intent.ACTION_PICK, android.provider.ContactsContract.Contacts.CONTENT_URI)
        startActivityForResult(intent, 501)
    }

    private fun confirmRemoveMember(contact: BroadcastContact) {
        AlertDialog.Builder(this)
            .setTitle("Remove Contact")
            .setMessage("Remove ${contact.name} from this list?")
            .setPositiveButton("Remove") { _, _ ->
                val list = BroadcastListHelper.getList(this, listId) ?: return@setPositiveButton
                BroadcastListHelper.saveList(this, list.copy(contacts = list.contacts - contact))
                reload()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun promptComposeAndSend() {
        val list = BroadcastListHelper.getList(this, listId) ?: return
        if (list.contacts.isEmpty()) {
            Toast.makeText(this, "Add at least one contact first", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(this, TextEntryActivity::class.java)
        intent.putExtra(TextEntryActivity.EXTRA_TITLE, "Message to ${list.contacts.size} people")
        intent.putExtra(TextEntryActivity.EXTRA_SEND_MODE, true)
        startActivityForResult(intent, 502)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            501 -> {
                val contactUri = data?.data
                if (resultCode == Activity.RESULT_OK && contactUri != null) {
                    Thread {
                        val added = loadContact(contactUri)
                        runOnUiThread {
                            if (isFinishing || isDestroyed) return@runOnUiThread
                            if (added != null) {
                                val list = BroadcastListHelper.getList(this, listId)
                                if (list != null) BroadcastListHelper.importInto(this, listId, listOf(added))
                                reload()
                            } else {
                                Toast.makeText(this, "Couldn't read that contact", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }.start()
                }
            }
            502 -> {
                if (resultCode == Activity.RESULT_OK) {
                    val body = data?.getStringExtra(TextEntryActivity.EXTRA_RESULT_TEXT)?.trim()
                    if (!body.isNullOrEmpty()) sendBroadcast(body)
                }
            }
            REQUEST_CSV -> {
                val uri = data?.data
                if (resultCode == Activity.RESULT_OK && uri != null) {
                    CsvImport.run(this, uri, listId) { summary ->
                        reload()
                        CsvImport.showSummary(this, summary)
                    }
                }
            }
            503 -> {
                if (resultCode == Activity.RESULT_OK) {
                    val newName = data?.getStringExtra(TextEntryActivity.EXTRA_RESULT_TEXT)?.trim()
                    if (!newName.isNullOrEmpty()) {
                        val list = BroadcastListHelper.getList(this, listId)
                        if (list != null) {
                            BroadcastListHelper.saveList(this, list.copy(name = newName))
                            reload()
                        }
                    }
                }
            }
        }
    }

    private fun loadContact(contactUri: android.net.Uri): BroadcastContact? {
        return try {
            val cursor = contentResolver.query(
                contactUri,
                arrayOf(
                    android.provider.ContactsContract.Contacts.DISPLAY_NAME,
                    android.provider.ContactsContract.Contacts._ID
                ),
                null, null, null
            )
            cursor?.use {
                if (!it.moveToFirst()) return null
                val name = it.getString(0) ?: return null
                val contactId = it.getString(1) ?: return null
                val phoneCursor = contentResolver.query(
                    android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    arrayOf(android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER),
                    "${android.provider.ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
                    arrayOf(contactId), null
                )
                phoneCursor?.use { pc ->
                    if (pc.moveToFirst()) {
                        val number = pc.getString(0) ?: return null
                        BroadcastContact(name, number)
                    } else null
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Confirms, then sends the message to each member as their own
     *  text (BroadcastSender) — replies land in that person's own thread.
     *  Paced, with progress in a notification, so it carries on with the
     *  screen closed. */
    private fun sendBroadcast(body: String) {
        val list = BroadcastListHelper.getList(this, listId) ?: return
        if (BroadcastSender.isRunning) {
            Toast.makeText(this, "Another broadcast is still sending", Toast.LENGTH_LONG).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Send to ${list.contacts.size} people?")
            .setMessage("Each person gets their own text, a few seconds apart. Progress shows in the notifications.")
            .setPositiveButton("Send") { _, _ ->
                if (BroadcastSender.start(this, list, body)) {
                    Toast.makeText(this, "Sending to ${list.contacts.size} people…", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Another broadcast is still sending", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun pickCsv() {
        try {
            startActivityForResult(CsvImport.pickIntent(), REQUEST_CSV)
        } catch (e: android.content.ActivityNotFoundException) {
            Toast.makeText(this, "No file browser found on this phone", Toast.LENGTH_LONG).show()
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_SOFT_RIGHT) {
            showOptions()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun showOptions() {
        val options = arrayOf("Import from CSV", "Rename List", "Delete List")
        AlertDialog.Builder(this)
            .setTitle("Options")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> pickCsv()
                    1 -> renameList()
                    2 -> confirmDeleteList()
                }
            }
            .show()
    }

    private fun renameList() {
        val list = BroadcastListHelper.getList(this, listId) ?: return
        val intent = Intent(this, TextEntryActivity::class.java)
        intent.putExtra(TextEntryActivity.EXTRA_TITLE, "Rename List")
        intent.putExtra(TextEntryActivity.EXTRA_INITIAL_TEXT, list.name)
        intent.putExtra(TextEntryActivity.EXTRA_ALLOW_VOICE, false)
        startActivityForResult(intent, 503)
    }

    private fun confirmDeleteList() {
        val list = BroadcastListHelper.getList(this, listId) ?: return
        AlertDialog.Builder(this)
            .setTitle("Delete List")
            .setMessage("Delete \"${list.name}\"? This can't be undone.")
            .setPositiveButton("Delete") { _, _ ->
                BroadcastListHelper.deleteList(this, listId)
                finish()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
