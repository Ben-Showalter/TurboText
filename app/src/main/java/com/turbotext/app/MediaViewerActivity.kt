package com.turbotext.app

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity

/**
 * Full-screen viewer for a picture or video attachment.
 *
 * Keys: Center zooms a picture (fit ↔ fill) or plays/pauses a video,
 * Left/Right step through a message's pictures when it has several,
 * Right softkey saves the one showing, Back/Clear closes.
 * The layout is built in code — it's two views and a softkey bar, not
 * worth a separate XML file.
 */
class MediaViewerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URI = "uri"
        const val EXTRA_MIME = "mime"
        /** All of a message's pictures (String array), to page through. */
        const val EXTRA_IMAGE_URIS = "image_uris"
    }

    private var pages: List<Uri> = emptyList()
    private var page = 0
    private var pageLabel: TextView? = null

    private lateinit var uri: Uri
    private var mime: String = "image/*"
    private var imageView: ImageView? = null
    private var videoView: VideoView? = null
    private lateinit var softCenter: TextView
    private var zoomed = false

    private val isVideo get() = mime.startsWith("video/")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        uri = intent.getStringExtra(EXTRA_URI)?.let { Uri.parse(it) } ?: run { finish(); return }
        mime = intent.getStringExtra(EXTRA_MIME) ?: contentResolver.getType(uri) ?: "image/*"

        val theme = ThemeHelper.getCurrentTheme(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }
        val content = FrameLayout(this)
        root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        if (isVideo) {
            val v = VideoView(this)
            content.addView(v, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER
            ))
            v.setVideoURI(uri)
            v.setOnPreparedListener { it.start(); updateCenterLabel() }
            v.setOnCompletionListener { updateCenterLabel() }
            v.setOnErrorListener { _, _, _ ->
                Toast.makeText(this, "Can't play this video", Toast.LENGTH_SHORT).show()
                true
            }
            videoView = v
        } else {
            val iv = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
            content.addView(iv, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            ))
            imageView = iv
            pages = intent.getStringArrayExtra(EXTRA_IMAGE_URIS)?.map { Uri.parse(it) }.orEmpty()
            page = pages.indexOf(uri).coerceAtLeast(0)
            if (pages.size > 1) {
                // "2 of 3", top center over the picture.
                pageLabel = TextView(this).apply {
                    setTextColor(Color.WHITE)
                    setBackgroundColor(0xB3000000.toInt())
                    textSize = 16f
                    setPadding(dp(10), dp(2), dp(10), dp(2))
                }
                content.addView(pageLabel, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP or Gravity.CENTER_HORIZONTAL
                ))
                updatePageLabel()
            }
            loadImage(iv)
        }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(theme.surface)
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        fun label(text: String, gravity: Int) = TextView(this).apply {
            this.text = text
            this.gravity = gravity
            setTextColor(theme.accent)
            textSize = 16f
        }
        bar.addView(label("Back", Gravity.START), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        softCenter = label("", Gravity.CENTER).apply { setTypeface(typeface, android.graphics.Typeface.BOLD) }
        bar.addView(softCenter, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(label("Save", Gravity.END), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(bar)

        setContentView(root)
        updateCenterLabel()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /** Decodes no larger than ~2× the screen so a 12 MP photo doesn't
     *  allocate tens of MB on a 2 GB phone. */
    private fun loadImage(target: ImageView) {
        val maxSide = maxOf(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels) * 2
        val source = uri
        Thread {
            val bmp = try {
                decodeSampled(source, maxSide)
            } catch (e: Exception) {
                null
            }
            runOnUiThread {
                // Paged on while this was decoding — a newer load owns the view.
                if (source != uri) return@runOnUiThread
                if (bmp != null) target.setImageBitmap(bmp)
                else Toast.makeText(this, "Couldn't open picture", Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    private fun decodeSampled(source: Uri, maxSide: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(source)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return contentResolver.openInputStream(source)?.use { BitmapFactory.decodeStream(it, null, opts) }
    }

    private fun updatePageLabel() {
        pageLabel?.text = "${page + 1} of ${pages.size}"
    }

    /** Shows the next (+1) or previous (-1) picture, wrapping around. */
    private fun turnPage(step: Int) {
        val iv = imageView ?: return
        if (pages.size < 2) return
        page = (page + step + pages.size) % pages.size
        uri = pages[page]
        iv.setImageDrawable(null)
        updatePageLabel()
        loadImage(iv)
    }

    private fun updateCenterLabel() {
        softCenter.text = when {
            isVideo -> if (videoView?.isPlaying == true) "Pause" else "Play"
            zoomed -> "Fit"
            else -> "Zoom"
        }
    }

    private fun onCenter() {
        val v = videoView
        if (v != null) {
            if (v.isPlaying) v.pause() else v.start()
        } else {
            zoomed = !zoomed
            imageView?.scaleType = if (zoomed) ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.FIT_CENTER
        }
        updateCenterLabel()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)
        return when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { onCenter(); true }
            KeyEvent.KEYCODE_SOFT_RIGHT -> { saveToPhone(); true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { turnPage(1); true }
            KeyEvent.KEYCODE_DPAD_LEFT -> { turnPage(-1); true }
            KeyEvent.KEYCODE_SOFT_LEFT, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_DEL -> { finish(); true }
            else -> super.dispatchKeyEvent(event)
        }
    }

    /** Copies the raw attachment bytes into Pictures/ or Movies/ via
     *  MediaStore, so the original quality is kept and it shows up in
     *  the Gallery straight away. */
    private fun saveToPhone() {
        Thread {
            val ok = MediaSaver.save(this, uri, mime)
            runOnUiThread {
                Toast.makeText(this, if (ok) "Saved to phone" else "Couldn't save", Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    override fun onPause() {
        super.onPause()
        videoView?.pause()
        updateCenterLabel()
    }
}

/** Saves any attachment to the shared media folders. Used by the viewer
 *  and by the conversation Options menu. */
object MediaSaver {
    fun save(context: android.content.Context, source: Uri, mimeIn: String?): Boolean {
        val resolver = context.contentResolver
        val mime = mimeIn ?: resolver.getType(source) ?: "application/octet-stream"
        val ext = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "bin"
        val name = "TurboText_${System.currentTimeMillis()}.$ext"
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val (collection, dir) = when {
                    mime.startsWith("image/") -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI to Environment.DIRECTORY_PICTURES
                    mime.startsWith("video/") -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI to Environment.DIRECTORY_MOVIES
                    mime.startsWith("audio/") -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI to Environment.DIRECTORY_MUSIC
                    else -> MediaStore.Downloads.EXTERNAL_CONTENT_URI to Environment.DIRECTORY_DOWNLOADS
                }
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "$dir/TurboText")
                }
                val dest = resolver.insert(collection, values) ?: return false
                resolver.openOutputStream(dest)?.use { out ->
                    resolver.openInputStream(source)?.use { it.copyTo(out) } ?: return false
                } ?: return false
                true
            } else {
                // Pre-Q: write straight into the public folder and let the
                // media scanner pick it up.
                val dirName = when {
                    mime.startsWith("image/") -> Environment.DIRECTORY_PICTURES
                    mime.startsWith("video/") -> Environment.DIRECTORY_MOVIES
                    mime.startsWith("audio/") -> Environment.DIRECTORY_MUSIC
                    else -> Environment.DIRECTORY_DOWNLOADS
                }
                @Suppress("DEPRECATION")
                val dir = java.io.File(Environment.getExternalStoragePublicDirectory(dirName), "TurboText")
                dir.mkdirs()
                val file = java.io.File(dir, name)
                resolver.openInputStream(source)?.use { input ->
                    file.outputStream().use { input.copyTo(it) }
                } ?: return false
                android.media.MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf(mime), null)
                true
            }
        } catch (e: Exception) {
            android.util.Log.w("TurboTextAttach", "save failed", e)
            false
        }
    }
}
