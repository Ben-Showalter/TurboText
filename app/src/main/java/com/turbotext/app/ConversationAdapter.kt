package com.turbotext.app

import android.content.Context
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import java.text.DateFormat

/**
 * Conversation list rows (main list and Group Messages).
 *
 * Built for smooth D-pad scrolling on a slow phone: list refreshes are
 * diffed so only rows that actually changed are rebound, listeners are
 * created once per row view rather than on every bind, and the theme is
 * read once per refresh instead of once per row.
 */
class ConversationAdapter(
    private var items: List<Conversation>,
    private val onSelected: (Conversation) -> Unit,
    private val onFocusChanged: (Conversation?) -> Unit = {}
) : RecyclerView.Adapter<ConversationAdapter.VH>() {

    init {
        setHasStableIds(true)
    }

    private var theme: AppTheme? = null
    private var recyclerView: RecyclerView? = null
    /** Conversation that had focus, so a refresh that moves it can hand
     *  focus back to the same conversation. */
    private var focusedThreadId: Long? = null

    override fun onAttachedToRecyclerView(rv: RecyclerView) { recyclerView = rv }
    override fun onDetachedFromRecyclerView(rv: RecyclerView) { recyclerView = null }
    private var showAvatars = true
    private val timeFormat: DateFormat = DateFormat.getTimeInstance(DateFormat.SHORT)
    private val dateFormat: DateFormat = DateFormat.getDateInstance(DateFormat.SHORT)

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val avatar: ImageView = view.findViewById(R.id.avatar)
        val name: TextView = view.findViewById(R.id.name)
        val time: TextView = view.findViewById(R.id.time)
        val snippet: TextView = view.findViewById(R.id.snippet)
        val selectionBar: View = view.findViewById(R.id.selectionBar)
        var appliedTheme: AppTheme? = null

        private fun current(): Conversation? =
            bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }?.let { items.getOrNull(it) }

        init {
            view.isFocusable = true
            view.isFocusableInTouchMode = true
            view.setOnClickListener { current()?.let(onSelected) }
            // Center/enter key on the focused row also opens it.
            view.setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN &&
                    (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)
                ) {
                    current()?.let(onSelected)
                    true
                } else false
            }
            // Accent bar on the right edge plus a row tint — the bar
            // alone was hard to see on light themes. Scrolling the row
            // into view is RowSnapLayoutManager's job, not this
            // listener's.
            view.setOnFocusChangeListener { _, hasFocus ->
                applyFocus(this, hasFocus)
                if (hasFocus) {
                    val c = current()
                    focusedThreadId = c?.threadId
                    onFocusChanged(c)
                }
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_conversation, parent, false)
        return VH(view)
    }

    private fun themeFor(context: Context): AppTheme =
        theme ?: ThemeHelper.getCurrentTheme(context).also {
            theme = it
            showAvatars = SettingsHelper.isShowAvatars(context)
        }

    private fun applyFocus(holder: VH, hasFocus: Boolean) {
        val t = themeFor(holder.itemView.context)
        holder.selectionBar.setBackgroundColor(if (hasFocus) t.accent else android.graphics.Color.TRANSPARENT)
        holder.itemView.setBackgroundColor(if (hasFocus) t.surface2 else android.graphics.Color.TRANSPARENT)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val context = holder.itemView.context
        val t = themeFor(context)
        if (holder.appliedTheme != t) {
            ThemeHelper.apply(holder.itemView, context)
            holder.appliedTheme = t
        }

        val convo = items[position]
        if (convo.unread) {
            holder.name.text = "● ${convo.displayName}"
            holder.name.setTypeface(null, android.graphics.Typeface.BOLD)
            holder.name.setTextColor(t.accent)
        } else {
            holder.name.text = convo.displayName
            holder.name.setTypeface(null, android.graphics.Typeface.NORMAL)
            holder.name.setTextColor(t.textPrimary)
        }
        holder.snippet.text = convo.snippet
        holder.time.text = formatTime(convo.date)

        if (showAvatars) {
            holder.avatar.visibility = View.VISIBLE
            AvatarLoader.bind(holder.avatar, convo.address, convo.displayName)
        } else {
            holder.avatar.visibility = View.GONE
        }

        applyFocus(holder, holder.itemView.hasFocus())

        // Only grabs focus if nothing in the list already has it — so a
        // refresh never yanks focus back to row 0 mid-scroll. Posted:
        // binding happens during layout, and a focus change there scrolls
        // the list mid-layout (rows drawn over each other).
        if (position == 0) {
            holder.itemView.post {
                val rv = recyclerView ?: return@post
                if (holder.bindingAdapterPosition == 0 && rv.findFocus() == null) holder.itemView.requestFocus()
            }
        }
    }

    private fun formatTime(date: Long): String {
        if (date <= 0) return ""
        return if (android.text.format.DateUtils.isToday(date)) timeFormat.format(date) else dateFormat.format(date)
    }

    override fun getItemCount() = items.size

    override fun getItemId(position: Int): Long = items[position].threadId

    /** Replaces the list, rebinding only the rows that changed. A theme
     *  or avatar-setting change since the last refresh rebinds all. */
    fun update(newItems: List<Conversation>, context: Context? = null) {
        val old = items
        items = newItems
        val ctx = context
        if (ctx != null) {
            val newTheme = ThemeHelper.getCurrentTheme(ctx)
            val newAvatars = SettingsHelper.isShowAvatars(ctx)
            if (newTheme != theme || newAvatars != showAvatars) {
                theme = newTheme
                showAvatars = newAvatars
                notifyDataSetChanged()
                return
            }
        }
        DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = old.size
            override fun getNewListSize() = newItems.size
            override fun areItemsTheSame(o: Int, n: Int) = old[o].threadId == newItems[n].threadId
            override fun areContentsTheSame(o: Int, n: Int) = old[o] == newItems[n]
        }).dispatchUpdatesTo(this)
        // If the update cost the focused conversation its focus (e.g. it
        // moved to the top), give it back once layout is done.
        val keep = focusedThreadId
        val rv = recyclerView
        if (keep != null && rv != null) {
            rv.post {
                if (rv.findFocus() == null) rv.findViewHolderForItemId(keep)?.itemView?.requestFocus()
            }
        }
    }
}
