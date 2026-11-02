package com.turbotext.app

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

/** One picture or video on the phone, for [MediaPickerActivity]. */
data class MediaItem(
    val uri: String,
    val mime: String,
    /** Seconds, as MediaStore's DATE_ADDED. */
    val dateAdded: Long,
    val isVideo: Boolean,
    val durationMs: Long = 0,
    val album: String? = null
)

object MediaItems {
    /** Pictures and videos together, newest first, as the Gallery shows them. */
    fun merge(pictures: List<MediaItem>, videos: List<MediaItem>): List<MediaItem> =
        (pictures + videos).sortedByDescending { it.dateAdded }

    /** A video's length on its thumbnail: "0:12", "3:05", "1:02:09". */
    fun durationLabel(ms: Long): String {
        val total = (ms + 500) / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    /** Album names in [items], most pictures first. */
    fun albums(items: List<MediaItem>): List<String> =
        items.mapNotNull { it.album?.takeIf { a -> a.isNotBlank() } }
            .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key }
}

/**
 * TurboText's own "Choose Picture or Video": every picture and video on
 * the phone in a grid, newest first. The phone's gallery app answered
 * this with its file browser, so TurboText reads the photo library
 * itself instead.
 *
 * Keys: the D-pad moves, 2/8 jump a page, OK chooses the highlighted one,
 * # marks several (OK then takes all the marked ones), the right
 * softkey picks an album, and Clear or the left softkey cancels. Hands
 * back content URIs the same way a gallery app would (data, or clipData
 * for several).
 */
class MediaPickerActivity : AppCompatActivity() {

    private companion object {
        const val COLUMNS = 3
        const val REQ_PERMISSION = 701
    }

    private val theme by lazy { ThemeHelper.getCurrentTheme(this) }
    private lateinit var header: TextView
    private lateinit var grid: RecyclerView
    private lateinit var message: TextView
    private val adapter = Adapter()

    private var all: List<MediaItem> = emptyList()
    private var shown: List<MediaItem> = emptyList()
    private var album: String? = null
    private var selected = 0
    private val marked = LinkedHashSet<String>()

    /** A release only counts if its press came here too, so the OK that
     *  opened this screen doesn't pick something at once. */
    private val pressed = HashSet<Int>()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        grid = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(this@MediaPickerActivity, COLUMNS)
            adapter = this@MediaPickerActivity.adapter
            isFocusable = false
            itemAnimator = null
        }
        message = TextView(this).apply {
            setTextColor(theme.textSecondary)
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(24), dp(16), dp(24))
            visibility = View.GONE
        }
        root.addView(message, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(grid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(theme.surface)
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        fun label(text: String, gravity: Int) = TextView(this).apply {
            this.text = text
            this.gravity = gravity
            setTextColor(theme.accent)
            textSize = 13f
        }
        bar.addView(label("Cancel", Gravity.START), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(label("Select", Gravity.CENTER), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(label("Albums", Gravity.END), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(bar)
        setContentView(root)
        header.text = "Pictures & Videos"

        if (hasPermission()) load() else askPermission()
    }

    // ---- Permission ----

    private fun permissionsNeeded(): Array<String> =
        if (Build.VERSION.SDK_INT >= 33) arrayOf("android.permission.READ_MEDIA_IMAGES", "android.permission.READ_MEDIA_VIDEO")
        else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)

    private fun hasPermission() = permissionsNeeded().all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun askPermission() = ActivityCompat.requestPermissions(this, permissionsNeeded(), REQ_PERMISSION)

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERMISSION) return
        if (hasPermission()) {
            load()
        } else {
            showMessage("Allow Photos access to choose pictures.\n\nPress OK to open TurboText's settings.")
        }
    }

    // ---- Loading ----

    private fun load() {
        showMessage("Loading…")
        val app = applicationContext
        Thread {
            val pictures = query(app, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, video = false)
            val videos = query(app, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, video = true)
            val items = MediaItems.merge(pictures, videos)
            android.util.Log.i("TurboTextAttach", "picker: ${pictures.size} pictures, ${videos.size} videos")
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                all = items
                applyAlbum(null)
            }
        }.start()
    }

    private fun query(context: android.content.Context, collection: Uri, video: Boolean): List<MediaItem> {
        val projection = mutableListOf(
            MediaStore.MediaColumns._ID, MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.DATE_ADDED, "bucket_display_name"
        )
        if (video) projection.add("duration")
        return try {
            context.contentResolver.query(
                collection, projection.toTypedArray(), null, null, "${MediaStore.MediaColumns.DATE_ADDED} DESC"
            )?.use { c ->
                val list = ArrayList<MediaItem>(c.count)
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    list.add(
                        MediaItem(
                            uri = ContentUris.withAppendedId(collection, id).toString(),
                            mime = c.getString(1) ?: if (video) "video/mp4" else "image/jpeg",
                            dateAdded = c.getLong(2),
                            isVideo = video,
                            durationMs = if (video) c.getLong(4) else 0,
                            album = c.getString(3)
                        )
                    )
                }
                list
            } ?: emptyList()
        } catch (e: Exception) {
            android.util.Log.w("TurboTextAttach", "couldn't read the photo library", e)
            emptyList()
        }
    }

    private fun applyAlbum(name: String?) {
        album = name
        shown = if (name == null) all else all.filter { it.album == name }
        selected = 0
        if (shown.isEmpty()) {
            showMessage(if (all.isEmpty()) "No pictures or videos on this phone." else "Nothing in $name.")
        } else {
            message.visibility = View.GONE
            grid.visibility = View.VISIBLE
        }
        adapter.notifyDataSetChanged()
        grid.scrollToPosition(0)
        updateHeader()
    }

    private fun showMessage(text: String) {
        message.text = text
        message.visibility = View.VISIBLE
        grid.visibility = View.GONE
    }

    private fun updateHeader() {
        val item = shown.getOrNull(selected)
        val count = "${album ?: "Pictures & Videos"} (${shown.size})"
        val markedText = if (marked.isEmpty()) "" else " · ${marked.size} marked"
        val date = item?.let {
            DateLabels.forMessage(it.dateAdded * 1000) + if (it.isVideo) " · video" else ""
        }.orEmpty()
        header.text = "$count$markedText\n$date"
    }

    // ---- Keys ----

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (event.action == KeyEvent.ACTION_DOWN) {
            pressed.add(code)
            when (code) {
                KeyEvent.KEYCODE_DPAD_LEFT -> move(-1)
                KeyEvent.KEYCODE_DPAD_RIGHT -> move(+1)
                KeyEvent.KEYCODE_DPAD_UP -> move(-COLUMNS)
                KeyEvent.KEYCODE_DPAD_DOWN -> move(+COLUMNS)
                KeyEvent.KEYCODE_2 -> move(-COLUMNS * 3)
                KeyEvent.KEYCODE_8 -> move(+COLUMNS * 3)
                KeyEvent.KEYCODE_POUND -> if (event.repeatCount == 0) toggleMark()
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BACK,
                KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_SOFT_LEFT, KeyEvent.KEYCODE_SOFT_RIGHT -> {}
                else -> return super.dispatchKeyEvent(event)
            }
            return true
        }
        if (event.action == KeyEvent.ACTION_UP) {
            if (!pressed.remove(code)) return true
            when (code) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> choose()
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_SOFT_LEFT -> {
                    setResult(Activity.RESULT_CANCELED)
                    finish()
                }
                KeyEvent.KEYCODE_SOFT_RIGHT -> chooseAlbum()
                else -> return super.dispatchKeyEvent(event)
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun move(by: Int) {
        if (shown.isEmpty()) return
        val old = selected
        selected = (selected + by).coerceIn(0, shown.size - 1)
        if (old == selected) return
        adapter.notifyItemChanged(old)
        adapter.notifyItemChanged(selected)
        grid.scrollToPosition(selected)
        updateHeader()
    }

    private fun toggleMark() {
        val item = shown.getOrNull(selected) ?: return
        if (!marked.remove(item.uri)) marked.add(item.uri)
        adapter.notifyItemChanged(selected)
        updateHeader()
    }

    /** OK: the marked ones if any, else the highlighted one. Without
     *  permission, OK opens TurboText's settings to allow it. */
    private fun choose() {
        if (!hasPermission()) {
            try {
                startActivity(
                    Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(Uri.fromParts("package", packageName, null))
                )
            } catch (e: Exception) {
            }
            finish()
            return
        }
        val uris = if (marked.isNotEmpty()) marked.map { Uri.parse(it) }
        else listOfNotNull(shown.getOrNull(selected)?.uri?.let { Uri.parse(it) })
        if (uris.isEmpty()) return
        val result = Intent().apply {
            data = uris.first()
            if (uris.size > 1) {
                clipData = ClipData.newRawUri("media", uris.first()).apply {
                    uris.drop(1).forEach { addItem(ClipData.Item(it)) }
                }
            }
        }
        setResult(Activity.RESULT_OK, result)
        finish()
    }

    private fun chooseAlbum() {
        if (all.isEmpty()) return
        val names = listOf<String?>(null) + MediaItems.albums(all)
        val labels = names.map { it ?: "All pictures & videos" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Album")
            .setItems(labels) { _, which -> applyAlbum(names[which]) }
            .show()
    }

    // ---- Grid ----

    private inner class Adapter : RecyclerView.Adapter<Adapter.Cell>() {
        inner class Cell(val frame: FrameLayout, val image: ImageView, val badge: TextView, val check: TextView) :
            RecyclerView.ViewHolder(frame)

        private val cellSize get() = grid.width.takeIf { it > 0 }?.div(COLUMNS) ?: dp(76)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Cell {
            val size = cellSize
            val frame = FrameLayout(parent.context).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, size)
                setPadding(dp(2), dp(2), dp(2), dp(2))
            }
            val image = ImageView(parent.context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(theme.surface2)
            }
            frame.addView(image, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            val badge = TextView(parent.context).apply {
                setTextColor(0xFFFFFFFF.toInt())
                setBackgroundColor(0x99000000.toInt())
                textSize = 10f
                setPadding(dp(3), 0, dp(3), 0)
            }
            frame.addView(badge, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START
            ))
            val check = TextView(parent.context).apply {
                text = "✓"
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 13f
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(theme.accent)
                }
            }
            frame.addView(check, FrameLayout.LayoutParams(dp(18), dp(18), Gravity.TOP or Gravity.END))
            return Cell(frame, image, badge, check)
        }

        override fun getItemCount() = shown.size

        override fun onBindViewHolder(holder: Cell, position: Int) {
            val item = shown[position]
            ThumbnailLoader.load(holder.image, item.uri, cellSize, isVideo = item.isVideo)
            holder.badge.visibility = if (item.isVideo) View.VISIBLE else View.GONE
            holder.badge.text = "▶ ${MediaItems.durationLabel(item.durationMs)}"
            holder.check.visibility = if (item.uri in marked) View.VISIBLE else View.GONE
            // The highlighted cell: an accent frame around it.
            holder.frame.setBackgroundColor(if (position == selected) theme.accent else android.graphics.Color.TRANSPARENT)
        }
    }
}
