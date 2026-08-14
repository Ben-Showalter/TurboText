package com.turbotext.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class BroadcastListsActivity : AppCompatActivity() {

    private lateinit var adapter: BroadcastListsAdapter
    private lateinit var list: RecyclerView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_broadcast_lists)

        ThemeHelper.apply(this)
        list = findViewById(R.id.broadcastListsList)
        list.layoutManager = LinearLayoutManager(this)
        adapter = BroadcastListsAdapter(
            emptyList(),
            onAddNew = { promptNewListName() },
            onSelectList = { bl ->
                val intent = Intent(this, BroadcastListDetailActivity::class.java)
                intent.putExtra("list_id", bl.id)
                startActivity(intent)
            }
        )
        list.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        adapter.update(BroadcastListHelper.getAllLists(this))
    }

    private fun promptNewListName() {
        val intent = Intent(this, TextEntryActivity::class.java)
        intent.putExtra(TextEntryActivity.EXTRA_TITLE, "New List Name")
        intent.putExtra(TextEntryActivity.EXTRA_ALLOW_VOICE, false)
        startActivityForResult(intent, 500)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 500 && resultCode == Activity.RESULT_OK) {
            val name = data?.getStringExtra(TextEntryActivity.EXTRA_RESULT_TEXT)?.trim()
            if (!name.isNullOrEmpty()) {
                val newList = BroadcastListHelper.createList(this, name)
                val intent = Intent(this, BroadcastListDetailActivity::class.java)
                intent.putExtra("list_id", newList.id)
                startActivity(intent)
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { finish(); return true }
            }
        }
        return super.dispatchKeyEvent(event)
    }
}
