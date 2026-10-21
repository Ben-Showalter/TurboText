package com.turbotext.app

import android.app.Dialog
import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * The emoji picker: a popup with the category name at the top and that
 * category's emojis in a five-wide grid, like the phone's own picker.
 *
 * Keys: the D-pad moves around the grid, and Left/Right past its edge go
 * to the previous/next category. OK inserts the highlighted emoji and
 * closes; # inserts it and stays open for another. Back, Clear or *
 * closes without inserting.
 */
class EmojiPickerDialog(
    context: Context,
    private val onPicked: (String) -> Unit,
    private val onClosed: () -> Unit
) : Dialog(context) {

    companion object {
        private const val COLUMNS = 5
    }

    private val theme = ThemeHelper.getCurrentTheme(context)
    private val categories = EmojiUsage.categories(context)
    private var category = 0
    private var index = 0

    private val title = TextView(context)
    private val position = TextView(context)
    private val scroll = ScrollView(context)
    private val grid = LinearLayout(context)
    private val cells = ArrayList<TextView>()

    /** Keys pressed while this was showing — a release only counts if its
     *  press came here too (see DigitEntryDialog). */
    private val pressed = HashSet<Int>()

    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()

    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(theme.surface)
            setPadding(dp(6), dp(6), dp(6), 0)
        }

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(6), dp(2), dp(6), dp(6))
        }
        title.apply {
            setTextColor(theme.textPrimary)
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            maxLines = 1
        }
        position.apply {
            setTextColor(theme.textSecondary)
            textSize = 11f
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        header.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(position, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(header)

        grid.orientation = LinearLayout.VERTICAL
        scroll.isVerticalScrollBarEnabled = false
        scroll.isFocusable = false
        scroll.addView(grid)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // Softkey hint, as on the phone's own picker.
        val bar = TextView(context).apply {
            text = "Select"
            setTextColor(theme.accent)
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(4))
        }
        root.addView(bar)

        setContentView(root)
        window?.setBackgroundDrawable(ColorDrawable(theme.surface))
        val metrics = context.resources.displayMetrics
        window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, (metrics.heightPixels * 0.85f).toInt())

        setOnKeyListener { _, keyCode, event ->
            when (event.action) {
                KeyEvent.ACTION_DOWN -> {
                    pressed.add(keyCode)
                    handleKeyDown(keyCode)
                }
                KeyEvent.ACTION_UP -> if (pressed.remove(keyCode)) handleKeyUp(keyCode) else true
                else -> true
            }
        }
        setOnDismissListener { onClosed() }
        showCategory(0, 0)
    }

    private fun showCategory(newCategory: Int, newIndex: Int) {
        category = (newCategory + categories.size) % categories.size
        val emojis = categories[category].emojis
        title.text = categories[category].name
        position.text = "${category + 1}/${categories.size}"
        grid.removeAllViews()
        cells.clear()
        emojis.chunked(COLUMNS).forEach { rowEmojis ->
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            for (col in 0 until COLUMNS) {
                val cell = TextView(context).apply {
                    text = rowEmojis.getOrNull(col) ?: ""
                    textSize = 24f
                    gravity = Gravity.CENTER
                    setTextColor(theme.textPrimary)
                }
                row.addView(cell, LinearLayout.LayoutParams(0, dp(44), 1f))
                if (col < rowEmojis.size) cells.add(cell)
            }
            grid.addView(row)
        }
        select(newIndex.coerceIn(0, emojis.lastIndex))
    }

    private fun select(newIndex: Int) {
        cells.getOrNull(index)?.background = null
        index = newIndex
        val cell = cells.getOrNull(index) ?: return
        cell.background = GradientDrawable().apply {
            setColor(theme.surface2)
            cornerRadius = dp(6).toFloat()
        }
        // Keep the highlighted row in view.
        scroll.post {
            val row = cell.parent as? View ?: return@post
            when {
                row.top < scroll.scrollY -> scroll.scrollTo(0, row.top)
                row.bottom > scroll.scrollY + scroll.height -> scroll.scrollTo(0, row.bottom - scroll.height)
            }
        }
    }

    private fun handleKeyDown(keyCode: Int): Boolean {
        val size = cells.size
        val row = index / COLUMNS
        val col = index % COLUMNS
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> if (index - COLUMNS >= 0) select(index - COLUMNS)
            KeyEvent.KEYCODE_DPAD_DOWN -> when {
                index + COLUMNS < size -> select(index + COLUMNS)
                // Last row is short: drop to its last emoji.
                row < (size - 1) / COLUMNS -> select(size - 1)
            }
            KeyEvent.KEYCODE_DPAD_LEFT ->
                if (col > 0) select(index - 1)
                else showCategoryAtRow(category - 1, row, lastColumn = true)
            KeyEvent.KEYCODE_DPAD_RIGHT ->
                if (col < COLUMNS - 1 && index + 1 < size) select(index + 1)
                else showCategoryAtRow(category + 1, row, lastColumn = false)
            KeyEvent.KEYCODE_POUND -> cells.getOrNull(index)?.let { onPicked(it.text.toString()) }
        }
        // Everything else waits for its key-up (below) or does nothing:
        // nothing types into the message while the picker is up.
        return true
    }

    /** Closing keys act on release, so the release never reaches the
     *  screen underneath: * there would change the typing mode, and Back
     *  could close the conversation. */
    private fun handleKeyUp(keyCode: Int): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                cells.getOrNull(index)?.let { onPicked(it.text.toString()) }
                dismiss()
            }
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_STAR,
            KeyEvent.KEYCODE_SOFT_LEFT, KeyEvent.KEYCODE_SOFT_RIGHT -> dismiss()
        }
        return true
    }

    /** Moves to [newCategory], keeping the highlight on the same row: its
     *  last emoji when arriving from the right, its first from the left. */
    private fun showCategoryAtRow(newCategory: Int, row: Int, lastColumn: Boolean) {
        val target = (newCategory + categories.size) % categories.size
        val count = categories[target].emojis.size
        val rowStart = minOf(row, (count - 1) / COLUMNS) * COLUMNS
        val newIndex = if (lastColumn) minOf(rowStart + COLUMNS - 1, count - 1) else rowStart
        showCategory(target, newIndex)
    }
}
