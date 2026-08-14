package com.turbotext.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class ConversationAdapter(
    private var items: List<Conversation>,
    private val onSelected: (Conversation) -> Unit,
    private val onFocusChanged: (Conversation?) -> Unit = {}
) : RecyclerView.Adapter<ConversationAdapter.VH>() {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.name)
        val snippet: TextView = view.findViewById(R.id.snippet)
        val selectionBar: View = view.findViewById(R.id.selectionBar)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_conversation, parent, false)
        view.isFocusable = true
        view.isFocusableInTouchMode = true
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        ThemeHelper.apply(holder.itemView, holder.itemView.context)

        val convo = items[position]
        val theme = ThemeHelper.getCurrentTheme(holder.itemView.context)
        if (convo.unread) {
            holder.name.text = "● ${convo.displayName}"
            holder.name.setTypeface(null, android.graphics.Typeface.BOLD)
            holder.name.setTextColor(theme.accent)
        } else {
            holder.name.text = convo.displayName
            holder.name.setTypeface(null, android.graphics.Typeface.NORMAL)
            holder.name.setTextColor(theme.textPrimary)
        }
        holder.snippet.text = convo.snippet
        holder.itemView.setOnClickListener { onSelected(convo) }
        // Center/enter key on the focused row also opens it
        holder.itemView.setOnKeyListener { _, keyCode, event ->
            if (event.action == android.view.KeyEvent.ACTION_DOWN &&
                (keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER || keyCode == android.view.KeyEvent.KEYCODE_ENTER)
            ) {
                onSelected(convo)
                true
            } else false
        }
        // Both the accent bar AND a row background tint — the bar alone
        // was hard to notice on light themes, where the accent color has
        // less contrast against a light background than it does on dark
        // themes against black.
        holder.itemView.setOnFocusChangeListener { _, hasFocus ->
            val theme = ThemeHelper.getCurrentTheme(holder.itemView.context)
            holder.selectionBar.setBackgroundColor(if (hasFocus) theme.accent else android.graphics.Color.TRANSPARENT)
            holder.itemView.setBackgroundColor(if (hasFocus) theme.surface2 else android.graphics.Color.TRANSPARENT)
            if (hasFocus) {
                onFocusChanged(convo)
                if (position == 0) {
                    // Belt-and-suspenders on top of requestRectangleOnScreen
                    // below — guarantees the RecyclerView's scroll offset is
                    // exactly 0 rather than "close enough", after a report
                    // that the top row could end up partially clipped under
                    // the header above it.
                    (holder.itemView.parent as? RecyclerView)?.scrollToPosition(0)
                }
                holder.itemView.requestRectangleOnScreen(
                    android.graphics.Rect(0, 0, holder.itemView.width, holder.itemView.height), true
                )
            }
        }
        // Only grabs focus if nothing in the list already has it — this
        // used to run unconditionally on every rebind (i.e. on every
        // single list refresh, not just the first load), which could
        // yank focus back to row 0 out from under the user mid-scroll
        // and snap the list to a scroll position that clipped the top
        // row under the header during the transition.
        if (position == 0) {
            val rv = holder.itemView.parent as? RecyclerView
            if (rv?.findFocus() == null) holder.itemView.requestFocus()
        }
    }

    override fun getItemCount() = items.size

    fun update(newItems: List<Conversation>) {
        items = newItems
        notifyDataSetChanged()
    }
}
