package com.turbotext.app

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns

/**
 * Something the user has attached to a message they're composing — a
 * photo, video, voice memo, contact card, or any other file. Either a
 * [uri] to read from or, for a contact card built in memory, raw [bytes].
 */
data class OutgoingAttachment(
    val mime: String,
    val name: String,
    val uri: Uri? = null,
    val bytes: ByteArray? = null
) {
    val isImage get() = mime.startsWith("image/")
    val isVideo get() = mime.startsWith("video/")
    val isAudio get() = mime.startsWith("audio/")
    val isVcard get() = mime == "text/x-vcard" || mime == "text/vcard"

    /** Short label for the "attached" indicator above the compose box. */
    val label: String
        get() = when {
            isImage -> "📷 Photo attached"
            isVideo -> "🎬 Video attached"
            isAudio -> "🎤 Audio attached"
            isVcard -> "👤 Contact attached"
            else -> "📎 $name attached"
        }

    override fun equals(other: Any?): Boolean =
        other is OutgoingAttachment && mime == other.mime && name == other.name && uri == other.uri &&
            bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = (mime.hashCode() * 31 + name.hashCode()) * 31 + (uri?.hashCode() ?: 0)

    companion object {
        /** Attachments as an Intent extra, for opening another screen
         *  with them. Each is copied into the cache first, so it stays
         *  readable there (another app's share grant may not carry over). */
        fun toHandoff(context: Context, items: List<OutgoingAttachment>): ArrayList<String> {
            val dir = java.io.File(context.cacheDir, "handoff").apply { mkdirs() }
            val out = ArrayList<String>()
            for (a in items) {
                try {
                    val file = java.io.File(dir, "${System.nanoTime()}-${a.name.replace(Regex("[^A-Za-z0-9._-]"), "_")}")
                    when {
                        a.bytes != null -> file.writeBytes(a.bytes)
                        a.uri != null -> context.contentResolver.openInputStream(a.uri)?.use { input ->
                            file.outputStream().use { input.copyTo(it) }
                        } ?: continue
                        else -> continue
                    }
                    out.add(org.json.JSONObject().put("mime", a.mime).put("name", a.name).put("path", file.path).toString())
                } catch (e: Exception) {
                    android.util.Log.w("TurboTextSend", "couldn't carry an attachment over", e)
                }
            }
            return out
        }

        fun fromHandoff(extra: List<String>?): List<OutgoingAttachment> = extra.orEmpty().mapNotNull {
            try {
                val o = org.json.JSONObject(it)
                OutgoingAttachment(o.getString("mime"), o.getString("name"), uri = Uri.fromFile(java.io.File(o.getString("path"))))
            } catch (e: Exception) {
                null
            }
        }

        /** Builds an attachment from a picked content:// URI, reading its
         *  real type and display name from the provider. */
        fun fromUri(context: Context, uri: Uri, fallbackMime: String = "application/octet-stream"): OutgoingAttachment {
            val mime = context.contentResolver.getType(uri) ?: fallbackMime
            var name: String? = null
            try {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) name = it.getString(0)
                }
            } catch (_: Exception) {
            }
            val ext = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "bin"
            return OutgoingAttachment(mime, name ?: "attachment_${System.currentTimeMillis()}.$ext", uri = uri)
        }
    }
}
