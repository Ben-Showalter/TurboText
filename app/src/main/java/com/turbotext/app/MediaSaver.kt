package com.turbotext.app

import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log

/**
 * Saves any attachment to the shared media folders, so it shows in the
 * Gallery (pictures, videos) or Files. Used by the viewer and by the
 * conversation's Options. [save]'s toDownloads puts it in
 * Download/TurboText ("Save to files") whatever its type.
 *
 * Written so the Gallery picks it up straight away and at the top:
 *  - the entry stays hidden (IS_PENDING) until the whole file is in, so
 *    the gallery can't catch it empty and keep a broken item;
 *  - it's dated now (DATE_TAKEN / DATE_ADDED), so it sorts first;
 *  - the type is cleaned up first ("image/jpg" from some phones is
 *    "image/jpeg"), so it gets a real extension MediaStore accepts.
 */
object MediaSaver {
    private const val TAG = "TurboTextAttach"

    /** Standard type for a non-standard one, and its file extension. */
    private val EXTENSIONS = linkedMapOf(
        "image/jpeg" to "jpg", "image/png" to "png", "image/gif" to "gif", "image/webp" to "webp",
        "image/bmp" to "bmp", "image/heic" to "heic", "image/heif" to "heif",
        "video/mp4" to "mp4", "video/3gpp" to "3gp", "video/3gpp2" to "3g2", "video/quicktime" to "mov",
        "video/webm" to "webm", "audio/mpeg" to "mp3", "audio/mp4" to "m4a", "audio/aac" to "aac",
        "audio/amr" to "amr", "audio/ogg" to "ogg", "audio/wav" to "wav", "audio/3gpp" to "3gp",
        "text/x-vcard" to "vcf", "text/plain" to "txt", "application/pdf" to "pdf"
    )
    private val ALIASES = mapOf(
        "image/jpg" to "image/jpeg", "image/pjpeg" to "image/jpeg", "image/x-png" to "image/png",
        "image/x-ms-bmp" to "image/bmp", "video/3gp" to "video/3gpp", "audio/mp3" to "audio/mpeg",
        "audio/x-wav" to "audio/wav", "audio/m4a" to "audio/mp4", "audio/x-m4a" to "audio/mp4",
        "text/vcard" to "text/x-vcard",
        // Only a wildcard known (and the file's bytes didn't say): the
        // usual type for each, so it still saves as something viewable.
        "image/*" to "image/jpeg", "video/*" to "video/mp4", "audio/*" to "audio/mp4"
    )

    /** (type, extension) to save [mime] as; unknown types keep their type
     *  and take the extension Android knows, or "bin". */
    fun normalize(mime: String?, knownExtension: (String) -> String? = ::systemExtension): Pair<String, String> {
        val clean = mime?.trim()?.lowercase()?.substringBefore(';')?.takeIf { it.contains('/') }
            ?: "application/octet-stream"
        val type = ALIASES[clean] ?: clean
        val ext = EXTENSIONS[type] ?: knownExtension(type) ?: "bin"
        return type to ext
    }

    /** A real type: not a wildcard (any image, any video) or a generic binary. */
    fun isSpecific(mime: String): Boolean {
        val m = mime.trim().lowercase()
        return m.contains('/') && !m.endsWith("/*") && m != "application/octet-stream"
    }

    /** A picture, video or sound's type from its first bytes. */
    fun sniff(head: ByteArray): String? {
        fun at(i: Int, vararg b: Int) = head.size >= i + b.size && b.indices.all { head[i + it].toInt() and 0xFF == b[it] }
        fun ascii(i: Int, text: String) = at(i, *text.map { it.code }.toIntArray())
        return when {
            at(0, 0xFF, 0xD8, 0xFF) -> "image/jpeg"
            at(0, 0x89, 0x50, 0x4E, 0x47) -> "image/png"
            ascii(0, "GIF8") -> "image/gif"
            ascii(0, "RIFF") && ascii(8, "WEBP") -> "image/webp"
            ascii(0, "BM") -> "image/bmp"
            ascii(4, "ftyp") && (ascii(8, "heic") || ascii(8, "heix") || ascii(8, "mif1")) -> "image/heic"
            ascii(4, "ftyp") && ascii(8, "3g2") -> "video/3gpp2"
            ascii(4, "ftyp") && ascii(8, "3gp") -> "video/3gpp"
            ascii(4, "ftyp") && ascii(8, "M4A") -> "audio/mp4"
            ascii(4, "ftyp") && ascii(8, "qt") -> "video/quicktime"
            ascii(4, "ftyp") -> "video/mp4"
            ascii(0, "#!AMR") -> "audio/amr"
            ascii(0, "ID3") || at(0, 0xFF, 0xFB) -> "audio/mpeg"
            ascii(0, "OggS") -> "audio/ogg"
            ascii(0, "%PDF") -> "application/pdf"
            else -> null
        }
    }

    private fun systemExtension(mime: String): String? =
        android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)

    fun save(context: android.content.Context, source: Uri, mimeIn: String?, toDownloads: Boolean = false): Boolean {
        val resolver = context.contentResolver
        // "image/*" (what the viewer knew) isn't a type a file can have —
        // saved as one, it became a .bin the Gallery never showed. Ask the
        // source, then read the file's first bytes.
        val given = listOf(mimeIn, try { resolver.getType(source) } catch (e: Exception) { null })
            .firstOrNull { it != null && isSpecific(it) }
        val sniffed = if (given == null) try {
            resolver.openInputStream(source)?.use { input ->
                val head = ByteArray(16)
                val n = input.read(head)
                if (n > 0) sniff(head.copyOf(n)) else null
            }
        } catch (e: Exception) {
            null
        } else null
        val (mime, ext) = normalize(given ?: sniffed ?: mimeIn)
        val name = "TurboText_${System.currentTimeMillis()}.$ext"
        val ok = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) saveQ(context, source, mime, name, toDownloads)
            else saveLegacy(context, source, mime, name, toDownloads)
        } catch (e: Exception) {
            Log.w(TAG, "save failed", e)
            false
        }
        Log.i(TAG, "save $name ($mime${if (toDownloads) ", to files" else ""}): ${if (ok) "ok" else "FAILED"}")
        return ok
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private fun saveQ(context: android.content.Context, source: Uri, mime: String, name: String, toDownloads: Boolean): Boolean {
        val resolver = context.contentResolver
        val (collection, dir) = when {
            toDownloads -> MediaStore.Downloads.EXTERNAL_CONTENT_URI to Environment.DIRECTORY_DOWNLOADS
            mime.startsWith("image/") -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI to Environment.DIRECTORY_PICTURES
            mime.startsWith("video/") -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI to Environment.DIRECTORY_MOVIES
            mime.startsWith("audio/") -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI to Environment.DIRECTORY_MUSIC
            else -> MediaStore.Downloads.EXTERNAL_CONTENT_URI to Environment.DIRECTORY_DOWNLOADS
        }
        val now = System.currentTimeMillis()
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "$dir/TurboText")
            put(MediaStore.MediaColumns.DATE_ADDED, now / 1000)
            put(MediaStore.MediaColumns.DATE_MODIFIED, now / 1000)
            if (mime.startsWith("image/") || mime.startsWith("video/")) put(MediaStore.MediaColumns.DATE_TAKEN, now)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val dest = resolver.insert(collection, values) ?: return false
        val copied = try {
            resolver.openOutputStream(dest)?.use { out ->
                resolver.openInputStream(source)?.use { it.copyTo(out) }
            } != null
        } catch (e: Exception) {
            Log.w(TAG, "copy failed", e)
            false
        }
        if (!copied) {
            // No half-written item left behind in the gallery.
            try { resolver.delete(dest, null, null) } catch (_: Exception) {}
            return false
        }
        resolver.update(dest, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        return true
    }

    /** Before Android 10: write straight into the public folder and let
     *  the media scanner pick it up. */
    private fun saveLegacy(context: android.content.Context, source: Uri, mime: String, name: String, toDownloads: Boolean): Boolean {
        val dirName = when {
            toDownloads -> Environment.DIRECTORY_DOWNLOADS
            mime.startsWith("image/") -> Environment.DIRECTORY_PICTURES
            mime.startsWith("video/") -> Environment.DIRECTORY_MOVIES
            mime.startsWith("audio/") -> Environment.DIRECTORY_MUSIC
            else -> Environment.DIRECTORY_DOWNLOADS
        }
        @Suppress("DEPRECATION")
        val dir = java.io.File(Environment.getExternalStoragePublicDirectory(dirName), "TurboText")
        dir.mkdirs()
        val file = java.io.File(dir, name)
        context.contentResolver.openInputStream(source)?.use { input ->
            file.outputStream().use { input.copyTo(it) }
        } ?: return false
        android.media.MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf(mime), null)
        return true
    }
}
