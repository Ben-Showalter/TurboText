package com.turbotext.app

import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class BroadcastListDetailAdapter(
    private var members: List<BroadcastContact>,
    private val onCompose: () -> Unit,
    private val onAddContact: () -> Unit,
    private val onSelectMember: (BroadcastContact) -> Unit
) : RecyclerView.Adapter<BroadcastListDetailAdapter.VH>() {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val text: TextView = view.findViewById(R.id.wordRowText)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_word, parent, false)
        return VH(view)
    }

    private fun bindAction(holder: VH, label: String, action: () -> Unit, requestFocusNow: Boolean) {
        holder.text.text = label
        holder.text.setTextColor(ThemeHelper.getCurrentTheme(holder.itemView.context).accent)
        holder.itemView.setOnClickListener { action() }
        holder.itemView.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN &&
                (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)
            ) {
                action(); true
            } else false
        }
        if (requestFocusNow) holder.itemView.requestFocus()
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        ThemeHelper.apply(holder.itemView, holder.itemView.context)
        ThemeHelper.applyRowFocusHighlight(holder.itemView, holder.itemView.context)
        when (position) {
            0 -> bindAction(holder, "📨 Compose & Send Message", onCompose, true)
            1 -> bindAction(holder, "+ Add Contact", onAddContact, false)
            else -> {
                val contact = members[position - 2]
                holder.text.text = "${contact.name} (${contact.number})"
                holder.text.setTextColor(ThemeHelper.getCurrentTheme(holder.itemView.context).textPrimary)
                holder.itemView.setOnClickListener { onSelectMember(contact) }
                holder.itemView.setOnKeyListener { _, keyCode, event ->
                    if (event.action == KeyEvent.ACTION_DOWN &&
                        (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)
                    ) {
                        onSelectMember(contact); true
                    } else false
                }
            }
        }
    }

    override fun getItemCount() = members.size + 2

    fun update(newMembers: List<BroadcastContact>) {
        members = newMembers
        notifyDataSetChanged()
    }
}
