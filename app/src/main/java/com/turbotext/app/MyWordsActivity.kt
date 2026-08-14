package com.turbotext.app

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class MyWordsActivity : AppCompatActivity() {

    private lateinit var engine: T9Engine
    private lateinit var adapter: MyWordsAdapter
    private lateinit var list: RecyclerView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_my_words)

        ThemeHelper.apply(this)
        engine = T9EngineHolder.get(this)
        list = findViewById(R.id.wordsList)
        list.layoutManager = LinearLayoutManager(this)
        adapter = MyWordsAdapter(
            emptyList(),
            onAddNew = { startActivity(Intent(this, AddWordActivity::class.java)) },
            onSelectWord = { word -> confirmRemove(word) }
        )
        list.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        adapter.update(engine.getUserWords())
    }

    private fun confirmRemove(word: String) {
        AlertDialog.Builder(this)
            .setTitle("Remove Word")
            .setMessage("Remove \"$word\" from the dictionary?")
            .setPositiveButton("Remove") { _, _ ->
                engine.removeUserWord(word)
                adapter.update(engine.getUserWords())
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
