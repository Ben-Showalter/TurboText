package com.turbotext.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.provider.MediaStore
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File

/**
 * Everything to do with picking or capturing an attachment, shared by the
 * conversation and new-message screens: take a photo, record a video,
 * choose a photo/video from the gallery, any file, or a contact card.
 * (Voice memos stay with each screen, since they're tied to its
 * recording UI.)
 *
 * The owning activity forwards onActivityResult to [handleResult]; the
 * finished attachments arrive through [onAttached] — several at once when
 * more than one photo/video is chosen from the gallery.
 */
class AttachmentPicker(
    private val activity: AppCompatActivity,
    private val onAttached: (List<OutgoingAttachment>) -> Unit
) {
    companion object {
        const val REQ_PHOTO = 400
        const val REQ_GALLERY = 401
        const val REQ_CONTACT = 403
        const val REQ_VIDEO = 404
        const val REQ_FILE = 405
        private const val REQ_CAMERA_PERMISSION = 300

        /** Long enough for a short message, short enough that it
         *  compresses into an MMS without looking like mush. */
        private const val MAX_VIDEO_SECONDS = 30
    }

    /** Where the camera app was asked to save the photo/video being taken. */
    private var captureUri: Uri? = null
    private var captureMime = "image/jpeg"

    /** "Attach" submenu. [onAudio] starts the screen's own voice-memo
     *  recorder. */
    fun showAttachMenu(onAudio: () -> Unit) {
        val options = arrayOf("Photo or Video from Gallery", "Audio Recording", "Contact", "File")
        AlertDialog.Builder(activity)
            .setTitle("Attach")
            .setItems(options) { _, which ->
                when (options[which]) {
                    "Photo or Video from Gallery" -> pickFromGallery()
                    "Audio Recording" -> onAudio()
                    "Contact" -> pickContact()
                    "File" -> pickFile()
                }
            }
            .show()
    }

    private fun hasCamera(): Boolean {
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) return true
        ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.CAMERA), REQ_CAMERA_PERMISSION)
        return false
    }

    private fun newCaptureUri(ext: String): Uri {
        val dir = File(activity.cacheDir, "camera").apply { mkdirs() }
        val file = File(dir, "mms_${System.currentTimeMillis()}.$ext")
        return FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", file)
    }

    private fun launch(intent: Intent, requestCode: Int, whatsMissing: String) {
        if (intent.resolveActivity(activity.packageManager) != null) {
            activity.startActivityForResult(intent, requestCode)
        } else {
            Toast.makeText(activity, "No $whatsMissing app available", Toast.LENGTH_SHORT).show()
        }
    }

    fun takePhoto() {
        if (!hasCamera()) return
        val uri = newCaptureUri("jpg")
        captureUri = uri
        captureMime = "image/jpeg"
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, uri)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        launch(intent, REQ_PHOTO, "camera")
    }

    /** Asks the camera for a short, low-quality clip — it'll be
     *  re-encoded to fit an MMS anyway, so starting small makes that
     *  faster and better-looking. */
    fun recordVideo() {
        if (!hasCamera()) return
        val uri = newCaptureUri("mp4")
        captureUri = uri
        captureMime = "video/mp4"
        val intent = Intent(MediaStore.ACTION_VIDEO_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, uri)
            putExtra(MediaStore.EXTRA_DURATION_LIMIT, MAX_VIDEO_SECONDS)
            putExtra(MediaStore.EXTRA_VIDEO_QUALITY, 0)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        launch(intent, REQ_VIDEO, "camera")
    }

    fun pickFromGallery() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*"))
            // Pickers that support it let you choose several; the rest
            // still return one, and Attach can be used again to add more.
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        activity.startActivityForResult(Intent.createChooser(intent, "Choose Photos or Videos"), REQ_GALLERY)
    }

    fun pickFile() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "*/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        activity.startActivityForResult(Intent.createChooser(intent, "Choose File"), REQ_FILE)
    }

    fun pickContact() {
        activity.startActivityForResult(Intent(Intent.ACTION_PICK, ContactsContract.Contacts.CONTENT_URI), REQ_CONTACT)
    }

    /** Returns true if [requestCode] was one of ours. */
    fun handleResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        val ok = resultCode == android.app.Activity.RESULT_OK
        when (requestCode) {
            REQ_PHOTO, REQ_VIDEO -> {
                val target = captureUri
                captureUri = null
                if (!ok) return true
                // Some camera apps ignore EXTRA_OUTPUT for video and hand
                // back their own content:// URI instead.
                val uri = data?.data ?: target ?: return true
                val name = "${if (requestCode == REQ_VIDEO) "video" else "photo"}_${System.currentTimeMillis()}." +
                    (if (requestCode == REQ_VIDEO) "mp4" else "jpg")
                onAttached(listOf(OutgoingAttachment(captureMime, name, uri = uri)))
            }
            REQ_GALLERY, REQ_FILE -> {
                if (!ok) return true
                // A multiple choice comes back in clipData; a single one
                // in data (some pickers fill both for one item).
                val uris = ArrayList<Uri>()
                data?.clipData?.let { clip ->
                    for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let { uris.add(it) }
                }
                if (uris.isEmpty()) data?.data?.let { uris.add(it) }
                if (uris.isNotEmpty()) onAttached(uris.map { OutgoingAttachment.fromUri(activity, it) })
            }
            REQ_CONTACT -> {
                val contactUri = data?.data
                if (ok && contactUri != null) {
                    Thread {
                        val vcard = loadVcard(contactUri)
                        activity.runOnUiThread {
                            if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                            if (vcard != null) onAttached(listOf(vcard))
                            else Toast.makeText(activity, "Couldn't read that contact", Toast.LENGTH_SHORT).show()
                        }
                    }.start()
                }
            }
            else -> return false
        }
        return true
    }

    /** Exports a contact as a standard .vcf via Android's own vCard
     *  provider — the same mechanism the Contacts app uses to share one. */
    private fun loadVcard(contactUri: Uri): OutgoingAttachment? {
        return try {
            activity.contentResolver.query(
                contactUri,
                arrayOf(ContactsContract.Contacts.LOOKUP_KEY, ContactsContract.Contacts.DISPLAY_NAME),
                null, null, null
            )?.use {
                if (!it.moveToFirst()) return null
                val lookupKey = it.getString(0)
                val name = it.getString(1) ?: "contact"
                val vcardUri = Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_VCARD_URI, lookupKey)
                val bytes = activity.contentResolver.openInputStream(vcardUri)?.use { s -> s.readBytes() } ?: return null
                OutgoingAttachment("text/x-vcard", name.replace(Regex("[^A-Za-z0-9]"), "_") + ".vcf", bytes = bytes)
            }
        } catch (e: Exception) {
            android.util.Log.w("TurboTextAttach", "failed to load vcard", e)
            null
        }
    }
}
