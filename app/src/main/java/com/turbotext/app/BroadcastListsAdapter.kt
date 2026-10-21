package com.turbotext.app

import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class BroadcastListsAdapter(
    private var lists: List<BroadcastList>,
    private val onAddNew: () -> Unit,
    private val onImportNew: () -> Unit,
    private val onSelectList: (BroadcastList) -> Unit
) : RecyclerView.Adapter<BroadcastListsAdapter.VH>() {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val text: TextView = view.findViewById(R.id.wordRowText)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_word, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        ThemeHelper.apply(holder.itemView, holder.itemView.context)
        ThemeHelper.applyRowFocusHighlight(holder.itemView, holder.itemView.context)
        if (position < ACTIONS) {
            val action = if (position == 0) onAddNew else onImportNew
            holder.text.text = if (position == 0) "+ New List" else "+ New List from CSV"
            holder.text.setTextColor(ThemeHelper.getCurrentTheme(holder.itemView.context).accent)
            holder.itemView.setOnClickListener { action() }
            holder.itemView.setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN &&
                    (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)
                ) {
                    action(); true
                } else false
            }
            if (position == 0 && lists.isEmpty()) holder.itemView.requestFocus()
            return
        }

        val list = lists[position - ACTIONS]
        holder.text.text = "${list.name} (${list.contacts.size})"
        holder.text.setTextColor(ThemeHelper.getCurrentTheme(holder.itemView.context).textPrimary)
        holder.itemView.setOnClickListener { onSelectList(list) }
        holder.itemView.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN &&
                (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)
            ) {
                onSelectList(list); true
            } else false
        }
        if (position == ACTIONS) holder.itemView.requestFocus()
    }

    override fun getItemCount() = lists.size + ACTIONS

    private companion object {
        /** Rows above the lists: New List, New List from CSV. */
        const val ACTIONS = 2
    }

    fun update(newLists: List<BroadcastList>) {
        lists = newLists
        notifyDataSetChanged()
    }
}
