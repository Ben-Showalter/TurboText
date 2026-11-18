package com.turbotext.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.FileOutputStream

/**
 * Contact Options > Conversation Background: what's behind one
 * conversation's messages. A grid — Random (the default), None, then
 * every picture in TurboText's pack — and Gallery (left softkey) for any
 * picture on the phone, through the same picker as attaching a picture.
 * A gallery picture is then positioned on a full-screen preview (D-pad
 * moves it, # zooms in, * zooms out) and saved as a copy for this conversation.
 */
class WallpaperPickerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_THREAD_ID = "threadId"
        const val EXTRA_NAME = "name"
        private const val COLUMNS = 3
        private const val REQ_GALLERY = 801
        /** Cells before the pack's pictures. */
        private const val FIXED_CELLS = 2

        fun open(context: Context, threadId: Long, name: String) {
            context.startActivity(
                Intent(context, WallpaperPickerActivity::class.java)
                    .putExtra(EXTRA_THREAD_ID, threadId)
                    .putExtra(EXTRA_NAME, name)
            )
        }
    }

    private val theme by lazy { ThemeHelper.getCurrentTheme(this) }
    private var threadId = -1L
    private var name = ""
    private var files: List<String> = emptyList()
    private val thumbs = HashMap<String, Bitmap>()
    private var selected = 0

    private lateinit var header: TextView
    private lateinit var grid: RecyclerView
    private lateinit var cropView: CropView
    private lateinit var hint: TextView
    private lateinit var leftLabel: TextView
    private lateinit var centerLabel: TextView
    private lateinit var rightLabel: TextView
    private val adapter = Adapter()

    /** A release only counts if its press came here too. */
    private val pressed = HashSet<Int>()
    private var positioning = false
    private var saving = false

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        threadId = intent.getLongExtra(EXTRA_THREAD_ID, -1L)
        name = intent.getStringExtra(EXTRA_NAME).orEmpty()
        if (threadId <= 0) {
            finish()
            return
        }
        files = ConversationWallpaper.files(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(theme.background)
        }
        header = TextView(this).apply {
            setTextColor(theme.textPrimary)
            setBackgroundColor(theme.surface)
            textSize = 14f
            setPadding(dp(8), dp(6), dp(8), dp(6))
            maxLines = 2
        }
        root.addView(header)

        val body = FrameLayout(this)
        grid = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(this@WallpaperPickerActivity, COLUMNS)
            adapter = this@WallpaperPickerActivity.adapter
            isFocusable = false
            itemAnimator = null
        }
        body.addView(grid, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        cropView = CropView(this).apply { visibility = View.GONE }
        body.addView(cropView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        hint = TextView(this).apply {
            text = "◀ ▲ ▼ ▶ move · # zoom in · * zoom out"
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0x99000000.toInt())
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(3), dp(4), dp(3))
            visibility = View.GONE
        }
        body.addView(hint, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM
        ))
        root.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(theme.surface)
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        fun label(gravity: Int) = TextView(this).apply {
            this.gravity = gravity
            setTextColor(theme.accent)
            textSize = 13f
        }
        leftLabel = label(Gravity.START)
        centerLabel = label(Gravity.CENTER)
        rightLabel = label(Gravity.END)
        for (v in listOf(leftLabel, centerLabel, rightLabel)) {
            bar.addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        root.addView(bar)
        setContentView(root)

        // Start on what's chosen now.
        selected = when (val c = ConversationWallpaper.getChoice(this, threadId)) {
            ConversationWallpaper.Choice.None -> 1
            is ConversationWallpaper.Choice.Pack -> files.indexOf(c.file).takeIf { it >= 0 }?.plus(FIXED_CELLS) ?: 0
            else -> 0
        }
        showGrid()
        grid.post { grid.scrollToPosition(selected) }
        loadThumbnails()
    }

    // ---- Grid ----

    private fun showGrid() {
        positioning = false
        cropView.visibility = View.GONE
        hint.visibility = View.GONE
        grid.visibility = View.VISIBLE
        leftLabel.text = "Gallery"
        centerLabel.text = "Select"
        rightLabel.text = "Cancel"
        updateHeader()
    }

    private fun cellLabel(position: Int): String = when (position) {
        0 -> "Random"
        1 -> "None"
        else -> ConversationWallpaper.label(ConversationWallpaper.Choice.Pack(files[position - FIXED_CELLS]))
    }

    private fun updateHeader() {
        val now = ConversationWallpaper.label(ConversationWallpaper.getChoice(this, threadId))
        header.text = "Conversation Background · $name\nNow: $now · ${cellLabel(selected)}"
    }

    private fun loadThumbnails() {
        val app = applicationContext
        val size = dp(76)
        Thread {
            for ((i, f) in files.withIndex()) {
                if (isFinishing || isDestroyed) return@Thread
                val b = ConversationWallpaper.packThumbnail(app, f, size, size * 4 / 3) ?: continue
                runOnUiThread {
                    thumbs[f] = b
                    adapter.notifyItemChanged(i + FIXED_CELLS)
                }
            }
        }.start()
    }

    private fun move(by: Int) {
        val count = adapter.itemCount
        if (count == 0) return
        val old = selected
        selected = (selected + by).coerceIn(0, count - 1)
        if (old == selected) return
        adapter.notifyItemChanged(old)
        adapter.notifyItemChanged(selected)
        grid.scrollToPosition(selected)
        updateHeader()
    }

    private fun chooseSelected() {
        val choice = when (selected) {
            0 -> ConversationWallpaper.Choice.Random
            1 -> ConversationWallpaper.Choice.None
            else -> ConversationWallpaper.Choice.Pack(files[selected - FIXED_CELLS])
        }
        ConversationWallpaper.setChoice(this, threadId, choice)
        Toast.makeText(this, "Conversation Background: ${ConversationWallpaper.label(choice)}", Toast.LENGTH_SHORT).show()
        finish()
    }

    // ---- Gallery ----

    private fun openGallery() {
        startActivityForResult(
            Intent(this, MediaPickerActivity::class.java).putExtra(MediaPickerActivity.EXTRA_FOR_BACKGROUND, true),
            REQ_GALLERY
        )
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_GALLERY || resultCode != Activity.RESULT_OK) return
        val uri = data?.data ?: return
        header.text = "Conversation Background · $name\nLoading the picture…"
        val app = applicationContext
        Thread {
            val bitmap = decodeForPositioning(app, uri)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (bitmap == null) {
                    Toast.makeText(this, "Couldn't open that picture", Toast.LENGTH_SHORT).show()
                    updateHeader()
                } else {
                    showPositioning(bitmap)
                }
            }
        }.start()
    }

    /** The gallery picture, upright, no bigger than needed to crop from. */
    private fun decodeForPositioning(context: Context, uri: Uri): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1600) sample *= 2
        val decoded = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        }
        decoded?.let { AttachmentPolicy.rotateUpright(context, uri, it) }
    } catch (e: Throwable) {
        android.util.Log.w("TurboTextWallpaper", "couldn't decode $uri", e)
        null
    }

    private fun showPositioning(bitmap: Bitmap) {
        positioning = true
        grid.visibility = View.GONE
        cropView.setPicture(bitmap)
        cropView.visibility = View.VISIBLE
        hint.visibility = View.VISIBLE
        leftLabel.text = ""
        centerLabel.text = "Save"
        rightLabel.text = "Back"
        header.text = "Conversation Background · $name\nGallery picture: move it into place"
    }

    private fun saveCustom() {
        if (saving) return
        val crop = cropView.cropped() ?: return
        saving = true
        centerLabel.text = "Saving…"
        val app = applicationContext
        val id = threadId
        Thread {
            val ok = try {
                val outW = 480
                val outH = (outW * crop.height.toFloat() / crop.width).toInt().coerceAtLeast(1)
                val scaled = Bitmap.createScaledBitmap(crop, outW, outH, true)
                FileOutputStream(ConversationWallpaper.customFile(app, id)).use {
                    scaled.compress(Bitmap.CompressFormat.JPEG, 85, it)
                }
                ConversationWallpaper.setChoice(app, id, ConversationWallpaper.Choice.Custom)
                true
            } catch (e: Exception) {
                android.util.Log.w("TurboTextWallpaper", "couldn't save the background", e)
                false
            }
            runOnUiThread {
                saving = false
                Toast.makeText(
                    this,
                    if (ok) "Conversation Background: Gallery Picture" else "Couldn't save the background",
                    Toast.LENGTH_SHORT
                ).show()
                if (ok) finish() else centerLabel.text = "Save"
            }
        }.start()
    }

    // ---- Keys ----

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (event.action == KeyEvent.ACTION_DOWN) {
            pressed.add(code)
            if (positioning) {
                when (code) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> cropView.pan(-1, 0)
                    KeyEvent.KEYCODE_DPAD_RIGHT -> cropView.pan(1, 0)
                    KeyEvent.KEYCODE_DPAD_UP -> cropView.pan(0, -1)
                    KeyEvent.KEYCODE_DPAD_DOWN -> cropView.pan(0, 1)
                    KeyEvent.KEYCODE_POUND -> cropView.zoom(1 / 1.12f)
                    KeyEvent.KEYCODE_STAR -> cropView.zoom(1.12f)
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BACK,
                    KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_SOFT_LEFT, KeyEvent.KEYCODE_SOFT_RIGHT -> {}
                    else -> return super.dispatchKeyEvent(event)
                }
                return true
            }
            when (code) {
                KeyEvent.KEYCODE_DPAD_LEFT -> move(-1)
                KeyEvent.KEYCODE_DPAD_RIGHT -> move(+1)
                KeyEvent.KEYCODE_DPAD_UP -> move(-COLUMNS)
                KeyEvent.KEYCODE_DPAD_DOWN -> move(+COLUMNS)
                KeyEvent.KEYCODE_2 -> move(-COLUMNS * 3)
                KeyEvent.KEYCODE_8 -> move(+COLUMNS * 3)
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BACK,
                KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_SOFT_LEFT, KeyEvent.KEYCODE_SOFT_RIGHT -> {}
                else -> return super.dispatchKeyEvent(event)
            }
            return true
        }
        if (event.action == KeyEvent.ACTION_UP) {
            if (!pressed.remove(code)) return true
            if (positioning) {
                when (code) {
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> saveCustom()
                    KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_SOFT_RIGHT -> if (!saving) showGrid()
                    else -> {}
                }
                return true
            }
            when (code) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> chooseSelected()
                KeyEvent.KEYCODE_SOFT_LEFT -> openGallery()
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_SOFT_RIGHT -> finish()
                else -> return super.dispatchKeyEvent(event)
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    // ---- Grid cells ----

    private inner class Adapter : RecyclerView.Adapter<Adapter.Cell>() {
        inner class Cell(val frame: FrameLayout, val image: ImageView, val text: TextView) : RecyclerView.ViewHolder(frame)

        private val cellWidth get() = grid.width.takeIf { it > 0 }?.div(COLUMNS) ?: dp(76)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Cell {
            val w = cellWidth
            val frame = FrameLayout(parent.context).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, w * 4 / 3)
                setPadding(dp(2), dp(2), dp(2), dp(2))
            }
            val image = ImageView(parent.context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(theme.surface2)
            }
            frame.addView(image, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            val text = TextView(parent.context).apply {
                setTextColor(theme.textPrimary)
                textSize = 14f
                gravity = Gravity.CENTER
            }
            frame.addView(text, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            return Cell(frame, image, text)
        }

        override fun getItemCount() = FIXED_CELLS + files.size

        override fun onBindViewHolder(holder: Cell, position: Int) {
            if (position < FIXED_CELLS) {
                holder.image.setImageDrawable(null)
                holder.text.text = cellLabel(position)
                holder.text.visibility = View.VISIBLE
            } else {
                holder.image.setImageBitmap(thumbs[files[position - FIXED_CELLS]])
                holder.text.visibility = View.GONE
            }
            holder.frame.setBackgroundColor(if (position == selected) theme.accent else android.graphics.Color.TRANSPARENT)
        }
    }

    // ---- Positioning a gallery picture ----

    /** Shows the part of the picture that will be the background, filling
     *  the screen and dimmed as in a conversation; [pan] and [zoom] move
     *  that part around the picture. */
    private inner class CropView(context: Context) : View(context) {
        private var picture: Bitmap? = null
        /** The visible part, in the picture's pixels. */
        private val crop = RectF()
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val dim = Paint().apply {
            val alpha = if (theme.isLight) 0x8C else 0x99
            color = (theme.background and 0x00FFFFFF) or (alpha shl 24)
        }

        fun setPicture(b: Bitmap) {
            picture = b
            crop.setEmpty()
            requestLayout()
            invalidate()
        }

        /** The largest window of this view's shape that fits the picture,
         *  centred — set once the view has a size. */
        private fun ensureCrop() {
            val b = picture ?: return
            if (!crop.isEmpty || width <= 0 || height <= 0) return
            val aspect = width.toFloat() / height
            var w = b.width.toFloat()
            var h = w / aspect
            if (h > b.height) {
                h = b.height.toFloat()
                w = h * aspect
            }
            crop.set((b.width - w) / 2, (b.height - h) / 2, (b.width + w) / 2, (b.height + h) / 2)
        }

        fun pan(dx: Int, dy: Int) {
            val b = picture ?: return
            ensureCrop()
            val step = crop.width() * 0.08f
            crop.offset(dx * step, dy * step)
            keepInside(b)
            invalidate()
        }

        /** [factor] > 1 shows more of the picture, < 1 less. */
        fun zoom(factor: Float) {
            val b = picture ?: return
            ensureCrop()
            val aspect = crop.width() / crop.height()
            val maxW = minOf(b.width.toFloat(), b.height * aspect)
            val w = (crop.width() * factor).coerceIn(maxW * 0.3f, maxW)
            val h = w / aspect
            val cx = crop.centerX()
            val cy = crop.centerY()
            crop.set(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
            keepInside(b)
            invalidate()
        }

        private fun keepInside(b: Bitmap) {
            if (crop.left < 0) crop.offset(-crop.left, 0f)
            if (crop.top < 0) crop.offset(0f, -crop.top)
            if (crop.right > b.width) crop.offset(b.width - crop.right, 0f)
            if (crop.bottom > b.height) crop.offset(0f, b.height - crop.bottom)
        }

        /** The chosen part as its own bitmap. */
        fun cropped(): Bitmap? {
            val b = picture ?: return null
            ensureCrop()
            val r = Rect(crop.left.toInt(), crop.top.toInt(), crop.right.toInt(), crop.bottom.toInt())
            r.intersect(0, 0, b.width, b.height)
            if (r.width() <= 0 || r.height() <= 0) return null
            return Bitmap.createBitmap(b, r.left, r.top, r.width(), r.height())
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            crop.setEmpty()
        }

        override fun onDraw(canvas: Canvas) {
            val b = picture ?: return
            ensureCrop()
            val src = Rect(crop.left.toInt(), crop.top.toInt(), crop.right.toInt(), crop.bottom.toInt())
            canvas.drawBitmap(b, src, Rect(0, 0, width, height), paint)
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dim)
        }
    }
}
