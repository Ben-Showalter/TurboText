package com.turbotext.app

import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class TrashBinActivity : AppCompatActivity() {

    private lateinit var repo: SmsRepository
    private lateinit var adapter: MessageAdapter
    private lateinit var list: RecyclerView
    private lateinit var emptyView: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_trash_bin)

        ThemeHelper.apply(this)
        repo = SmsRepository(this)
        list = findViewById(R.id.trashList)
        emptyView = findViewById(R.id.emptyView)
        list.layoutManager = LinearLayoutManager(this)
        adapter = MessageAdapter(emptyList())
        list.adapter = adapter

        load()
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun load() {
        Thread {
            val trashed = repo.getTrashedMessages()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                adapter.update(trashed)
                emptyView.visibility = if (trashed.isEmpty()) View.VISIBLE else View.GONE
            }
        }.start()
    }

    // This is a plain view-only list — no selection, just browsing — so
    // Up/Down just needs to scroll by a fixed step, same ¾" as used
    // elsewhere in the app.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            val step = (120 * resources.displayMetrics.density).toInt()
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> { list.scrollBy(0, -step); return true }
                KeyEvent.KEYCODE_DPAD_DOWN -> { list.scrollBy(0, step); return true }
            }
        }
        return super.dispatchKeyEvent(event)
    }
}
