package com.turbotext.app

import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class MyWordsAdapter(
    private var words: List<String>,
    private val onAddNew: () -> Unit,
    private val onSelectWord: (String) -> Unit
) : RecyclerView.Adapter<MyWordsAdapter.VH>() {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val text: TextView = view.findViewById(R.id.wordRowText)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_word, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        ThemeHelper.apply(holder.itemView, holder.itemView.context)
        ThemeHelper.applyRowFocusHighlight(holder.itemView, holder.itemView.context)
        if (position == 0) {
            holder.text.text = "+ Add New Word"
            holder.text.setTextColor(ThemeHelper.getCurrentTheme(holder.itemView.context).accent)
            holder.itemView.setOnClickListener { onAddNew() }
            holder.itemView.setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN &&
                    (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)
                ) {
                    onAddNew(); true
                } else false
            }
            if (words.isEmpty()) holder.itemView.requestFocus()
            return
        }

        val word = words[position - 1]
        holder.text.text = word
        holder.text.setTextColor(ThemeHelper.getCurrentTheme(holder.itemView.context).textPrimary)
        holder.itemView.setOnClickListener { onSelectWord(word) }
        holder.itemView.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN &&
                (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)
            ) {
                onSelectWord(word); true
            } else false
        }
        if (position == 1) holder.itemView.requestFocus()
    }

    override fun getItemCount() = words.size + 1

    fun update(newWords: List<String>) {
        words = newWords
        notifyDataSetChanged()
    }
}
