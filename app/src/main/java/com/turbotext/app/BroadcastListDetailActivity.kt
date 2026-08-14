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
                                if (list != null && list.contacts.none { it.number == added.number }) {
                                    BroadcastListHelper.saveList(this, list.copy(contacts = list.contacts + added))
                                }
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

    /** Sends the same message individually to each list member, reusing
     *  the same send logic as any normal single-recipient message —
     *  replies naturally land in that person's own existing thread,
     *  since as far as the phone/carrier is concerned each one is just
     *  an ordinary separate text. */
    private fun sendBroadcast(body: String) {
        val list = BroadcastListHelper.getList(this, listId) ?: return
        Toast.makeText(this, "Sending to ${list.contacts.size} people…", Toast.LENGTH_SHORT).show()
        Thread {
            for (contact in list.contacts) {
                try {
                    repo.sendMessage(contact.number, body)
                } catch (e: Exception) {
                    android.util.Log.w("TurboTextBroadcast", "failed to send to ${contact.number}", e)
                }
            }
            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    Toast.makeText(this, "Sent to ${list.contacts.size} people", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_SOFT_RIGHT) {
            showOptions()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun showOptions() {
        val options = arrayOf("Rename List", "Delete List")
        AlertDialog.Builder(this)
            .setTitle("Options")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> renameList()
                    1 -> confirmDeleteList()
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
