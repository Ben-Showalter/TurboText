package com.turbotext.app

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class AppPickerAdapter(
    private val apps: List<ApplicationInfo>,
    private val packageManager: PackageManager,
    private val onSelect: (ApplicationInfo) -> Unit
) : RecyclerView.Adapter<AppPickerAdapter.VH>() {

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
        val app = apps[position]
        holder.text.text = app.loadLabel(packageManager).toString()
        holder.itemView.setOnClickListener { onSelect(app) }
        holder.itemView.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN &&
                (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)
            ) {
                onSelect(app); true
            } else false
        }
        if (position == 0) holder.itemView.requestFocus()
    }

    override fun getItemCount() = apps.size
}
