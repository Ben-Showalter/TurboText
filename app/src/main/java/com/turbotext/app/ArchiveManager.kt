package com.turbotext.app

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.Telephony
import android.util.JsonReader
import android.util.JsonWriter
import android.util.Log
import com.google.android.mms.ContentType
import com.google.android.mms.pdu_alt.CharacterSets
import com.google.android.mms.pdu_alt.EncodedStringValue
import com.google.android.mms.pdu_alt.MultimediaMessagePdu
import com.google.android.mms.pdu_alt.PduBody
import com.google.android.mms.pdu_alt.PduPart
import com.google.android.mms.pdu_alt.PduPersister
import com.google.android.mms.pdu_alt.RetrieveConf
import com.google.android.mms.pdu_alt.SendReq
import java.io.File
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Settings > Archive Options: saving every conversation to the phone's
 * Documents folder, and putting an archive back.
 *
 * An archive is one folder, Documents/Archived conversations/<date time>/:
 *  - archive.json: the exact copy Import reads (ArchiveFormat);
 *  - media/: every picture, video, voice message and file;
 *  - "Open me.html": all the conversations as message bubbles, pictures
 *    shown, for reading in a web browser on a PC;
 *  - conversations/<name>.txt: each conversation as plain text.
 */
object ArchiveManager {
    private const val TAG = "TurboTextArchive"
    private const val JSON = "archive.json"

    fun archivesRoot(): File = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "Archived conversations"
    )

    data class Result(val dir: File, val messages: Int, val files: Int)

    // ---- Archiving ----

    /** Writes a new archive of every conversation. [progress] gets (done,
     *  total). Off the main thread. */
    fun archive(context: Context, progress: (Int, Int) -> Unit): Result {
        val created = System.currentTimeMillis()
        val dir = File(archivesRoot(), SimpleDateFormat("yyyy-MM-dd HH.mm", Locale.US).format(Date(created)))
        val media = File(dir, "media").apply { mkdirs() }
        val texts = File(dir, "conversations").apply { mkdirs() }
        val threads = threadNames(context)
        val total = count(context, Telephony.Sms.CONTENT_URI, null) +
            count(context, Telephony.Mms.CONTENT_URI, MMS_KEPT)
        var done = 0
        var files = 0
        val html = HtmlWriter(File(dir, "Open me.html"), created)

        JsonWriter(File(dir, JSON).bufferedWriter()).use { json ->
            json.setIndent(" ")
            ArchiveFormat.writeHeader(json, created, total)
            for ((thread, name) in threads) {
                val entries = threadEntries(context, thread, media) { files++ }
                if (entries.isEmpty()) continue
                html.conversation(name, entries)
                File(texts, safeFileName(name) + ".txt").writeText(plainText(name, entries))
                for (e in entries) {
                    ArchiveFormat.write(json, e)
                    done++
                    if (done % 25 == 0) progress(done, total)
                }
            }
            ArchiveFormat.writeFooter(json)
        }
        html.finish()
        progress(done, done)
        AppLog.event("Archive", "archived $done messages, $files files to ${dir.name}")
        return Result(dir, done, files)
    }

    /** MMS rows worth keeping: sent or downloaded messages, not delivery
     *  reports or "press OK to download" notices. */
    private val MMS_KEPT = "${Telephony.Mms.MESSAGE_TYPE} IN (128, 132)"

    private fun count(context: Context, uri: Uri, selection: String?): Int =
        context.contentResolver.query(uri, arrayOf("_id"), selection, null, null)?.use { it.count } ?: 0

    /** Every thread with its title: the nickname or the members' names. */
    private fun threadNames(context: Context): List<Pair<Long, String>> {
        val contacts = ContactIndex.load(context)
        val repo = SmsRepository(context)
        val ids = context.contentResolver.query(
            Uri.parse("content://mms-sms/conversations?simple=true"), arrayOf("_id"), null, null, "date DESC"
        )?.use { c -> generateSequence { if (c.moveToNext()) c.getLong(0) else null }.toList() } ?: emptyList()
        return ids.map { id ->
            val members = repo.getThreadParticipants(id)
            val name = GroupNicknameHelper.getNickname(context, id)
                ?: members.joinToString(", ") { contacts.nameFor(it) ?: it }.ifBlank { "Conversation $id" }
            id to name
        }
    }

    /** One thread's messages, oldest first; picture message files are
     *  copied into [media] ([onFile] counts them). */
    private fun threadEntries(context: Context, thread: Long, media: File, onFile: () -> Unit): List<ArchiveFormat.Entry> {
        val list = ArrayList<ArchiveFormat.Entry>()
        context.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(Telephony.Sms.TYPE, Telephony.Sms.ADDRESS, Telephony.Sms.DATE, Telephony.Sms.DATE_SENT, Telephony.Sms.READ, Telephony.Sms.BODY),
            "${Telephony.Sms.THREAD_ID} = ?", arrayOf(thread.toString()), "${Telephony.Sms.DATE} ASC"
        )?.use { c ->
            while (c.moveToNext()) {
                list.add(
                    ArchiveFormat.Entry(
                        kind = "sms", box = c.getInt(0), address = c.getString(1).orEmpty(), date = c.getLong(2),
                        dateSent = c.getLong(3), read = c.getInt(4) != 0, body = c.getString(5).orEmpty(), thread = thread
                    )
                )
            }
        }
        context.contentResolver.query(
            Telephony.Mms.CONTENT_URI,
            arrayOf(Telephony.Mms._ID, Telephony.Mms.MESSAGE_BOX, Telephony.Mms.DATE, Telephony.Mms.READ, Telephony.Mms.SUBJECT),
            "${Telephony.Mms.THREAD_ID} = ? AND $MMS_KEPT", arrayOf(thread.toString()), "${Telephony.Mms.DATE} ASC"
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                try {
                    list.add(
                        ArchiveFormat.Entry(
                            kind = "mms", box = c.getInt(1), date = c.getLong(2) * 1000L, read = c.getInt(3) != 0,
                            subject = c.getString(4), thread = thread,
                            from = addresses(context, id, 137).firstOrNull(),
                            to = addresses(context, id, 151), cc = addresses(context, id, 130),
                            parts = mmsParts(context, id, media, onFile)
                        )
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "couldn't archive picture message $id", e)
                }
            }
        }
        return list.sortedBy { it.date }
    }

    private fun addresses(context: Context, mmsId: Long, type: Int): List<String> =
        context.contentResolver.query(
            Uri.parse("content://mms/$mmsId/addr"), arrayOf("address"), "type = $type", null, null
        )?.use { c ->
            generateSequence { if (c.moveToNext()) c.getString(0) else null }
                .filter { it.isNotBlank() && it != "insert-address-token" }.toList()
        } ?: emptyList()

    private fun mmsParts(context: Context, mmsId: Long, media: File, onFile: () -> Unit): List<ArchiveFormat.Part> {
        val parts = ArrayList<ArchiveFormat.Part>()
        context.contentResolver.query(
            Uri.parse("content://mms/part"), arrayOf("_id", "ct", "text", "_data", "name", "cl"),
            "mid = ?", arrayOf(mmsId.toString()), "seq ASC"
        )?.use { c ->
            while (c.moveToNext()) {
                val partId = c.getLong(0)
                val mime = (c.getString(1) ?: "").substringBefore(';').trim().lowercase()
                if (mime == "application/smil" || mime.isEmpty()) continue
                val partUri = Uri.parse("content://mms/part/$partId")
                if (mime == "text/plain") {
                    val text = c.getString(2)?.takeIf { it.isNotEmpty() }
                        ?: if (c.getString(3) != null) context.contentResolver.openInputStream(partUri)?.use { it.readBytes().toString(Charsets.UTF_8) } else null
                    if (!text.isNullOrEmpty()) parts.add(ArchiveFormat.Part(mime, text = text))
                    continue
                }
                val name = (c.getString(4) ?: c.getString(5) ?: "part").substringAfterLast('/')
                val file = "media/$mmsId-$partId-${safeFileName(name)}"
                val copied = try {
                    context.contentResolver.openInputStream(partUri)?.use { input ->
                        File(media.parentFile, file).outputStream().use { input.copyTo(it) }
                    } != null
                } catch (e: Exception) {
                    false
                }
                if (copied) {
                    onFile()
                    parts.add(ArchiveFormat.Part(mime, name = name, file = file))
                }
            }
        }
        return parts
    }

    fun safeFileName(name: String): String =
        name.replace(Regex("[^A-Za-z0-9 ._-]"), "_").trim().take(60).ifEmpty { "conversation" }

    private fun stamp(ms: Long) = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(ms))

    private fun plainText(name: String, entries: List<ArchiveFormat.Entry>): String = buildString {
        appendLine("Conversation: $name")
        appendLine()
        for (e in entries) {
            val who = when {
                e.isOutgoing -> "Me"
                e.isMms -> e.from ?: "?"
                else -> e.address ?: "?"
            }
            append("[${stamp(e.date)}] $who: ${e.text}")
            for (p in e.parts) if (p.file != null) append("\n    (${p.mime}: ${p.file})")
            appendLine()
        }
    }

    /** "Open me.html": every conversation as bubbles, in one page. */
    private class HtmlWriter(file: File, created: Long) {
        private val body = File(file.parentFile, ".body.html.tmp").bufferedWriter()
        private val index = StringBuilder()
        private val out = file
        private val created = created
        private var n = 0

        fun conversation(name: String, entries: List<ArchiveFormat.Entry>) {
            n++
            index.append("<li><a href=\"#c$n\">${esc(name)}</a> <span>(${entries.size})</span></li>\n")
            body.write("<h2 id=\"c$n\">${esc(name)}</h2>\n")
            for (e in entries) {
                val who = if (e.isOutgoing) "me" else "them"
                val from = if (!e.isOutgoing && e.isMms) "${esc(e.from ?: "")} · " else ""
                body.write("<div class=\"$who\"><div class=\"b\">")
                if (e.text.isNotEmpty()) body.write("<p>${esc(e.text).replace("\n", "<br>")}</p>")
                for (p in e.parts) {
                    val f = p.file ?: continue
                    val src = esc(f.split('/').joinToString("/") { Uri.encode(it) })
                    body.write(
                        when {
                            p.mime.startsWith("image/") -> "<a href=\"$src\"><img src=\"$src\" alt=\"picture\"></a>"
                            p.mime.startsWith("video/") -> "<video controls src=\"$src\"></video>"
                            p.mime.startsWith("audio/") -> "<audio controls src=\"$src\"></audio>"
                            else -> "<a href=\"$src\">${esc(p.name ?: "file")}</a>"
                        }
                    )
                }
                body.write("<small>$from${esc(stamp(e.date))}</small></div></div>\n")
            }
        }

        fun finish() {
            body.close()
            val tmp = File(out.parentFile, ".body.html.tmp")
            out.bufferedWriter().use { w ->
                w.write(
                    "<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width\">" +
                        "<title>TurboText archive</title><style>" +
                        "body{font-family:sans-serif;max-width:760px;margin:auto;padding:12px;background:#f4f4f4;color:#222}" +
                        "h2{border-top:2px solid #ccc;padding-top:12px}li span{color:#777}" +
                        ".me,.them{display:flex;margin:6px 0}.me{justify-content:flex-end}" +
                        ".b{max-width:75%;padding:8px 10px;border-radius:12px;background:#fff}.me .b{background:#cfe3ff}" +
                        ".b p{margin:0 0 4px}.b img,.b video{max-width:100%;border-radius:8px;display:block}" +
                        "small{color:#777;font-size:11px}</style></head><body>"
                )
                w.write("<h1>TurboText archive</h1><p>Saved ${esc(stamp(created))}.</p><ul>\n$index</ul>\n")
                tmp.bufferedReader().use { it.copyTo(w) }
                w.write("</body></html>")
            }
            tmp.delete()
        }

        private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    }

    // ---- Import ----

    data class ArchiveInfo(val dir: File, val created: Long, val count: Int) {
        val label: String get() = "${stamp(created)} — %,d messages".format(count)
    }

    /** Archives that can be imported (with an archive.json), newest first. */
    fun listArchives(): List<ArchiveInfo> =
        (archivesRoot().listFiles() ?: emptyArray())
            .mapNotNull { dir ->
                val json = File(dir, JSON)
                if (!json.isFile) return@mapNotNull null
                try {
                    JsonReader(json.bufferedReader()).use { r ->
                        val h = ArchiveFormat.readHeader(r)
                        ArchiveInfo(dir, h.created, h.count)
                    }
                } catch (e: Exception) {
                    null
                }
            }
            .sortedByDescending { it.created }

    data class ImportResult(val imported: Int, val skipped: Int, val failed: Int)

    /** Puts archive [dir] back, skipping messages already on the phone.
     *  [progress] gets (done, total). Off the main thread. */
    fun import(context: Context, dir: File, progress: (Int, Int) -> Unit): ImportResult {
        var imported = 0
        var skipped = 0
        var failed = 0
        var total = 0
        var done = 0
        JsonReader(File(dir, JSON).bufferedReader()).use { r ->
            ArchiveFormat.read(r, onHeader = { total = it.count }) { e ->
                try {
                    val added = if (e.isMms) importMms(context, dir, e) else importSms(context, e)
                    if (added) imported++ else skipped++
                } catch (ex: Exception) {
                    failed++
                    Log.w(TAG, "couldn't import a message", ex)
                }
                done++
                if (done % 20 == 0) progress(done, total)
            }
        }
        progress(done, done)
        MessageCache.clear()
        ConversationListCache.clear()
        GroupThreadsCache.clear()
        ProviderChangeTracker.bump()
        MessageEvents.changed(null)
        AppLog.event("Archive", "imported ${dir.name}: $imported added, $skipped already there, $failed failed")
        return ImportResult(imported, skipped, failed)
    }

    private fun importSms(context: Context, e: ArchiveFormat.Entry): Boolean {
        val address = e.address ?: return false
        val exists = context.contentResolver.query(
            Telephony.Sms.CONTENT_URI, arrayOf("_id"),
            "${Telephony.Sms.ADDRESS} = ? AND ${Telephony.Sms.DATE} = ? AND ${Telephony.Sms.BODY} = ?",
            arrayOf(address, e.date.toString(), e.body.orEmpty()), null
        )?.use { it.count > 0 } ?: false
        if (exists) return false
        val values = ContentValues().apply {
            put(Telephony.Sms.ADDRESS, address)
            put(Telephony.Sms.BODY, e.body.orEmpty())
            put(Telephony.Sms.DATE, e.date)
            put(Telephony.Sms.DATE_SENT, e.dateSent)
            put(Telephony.Sms.TYPE, e.box)
            put(Telephony.Sms.READ, if (e.read) 1 else 0)
            put(Telephony.Sms.SEEN, 1)
            put(Telephony.Sms.THREAD_ID, Telephony.Threads.getOrCreateThreadId(context, address))
        }
        return context.contentResolver.insert(Telephony.Sms.CONTENT_URI, values) != null
    }

    private fun importMms(context: Context, dir: File, e: ArchiveFormat.Entry): Boolean {
        val dateSeconds = e.date / 1000
        val exists = context.contentResolver.query(
            Telephony.Mms.CONTENT_URI, arrayOf("_id"),
            "${Telephony.Mms.DATE} = ? AND ${Telephony.Mms.MESSAGE_BOX} = ? AND $MMS_KEPT",
            arrayOf(dateSeconds.toString(), e.box.toString()), null
        )?.use { it.count > 0 } ?: false
        if (exists) return false

        val body = PduBody()
        for ((i, p) in e.parts.withIndex()) {
            val data = when {
                p.text != null -> p.text.toByteArray(Charsets.UTF_8)
                p.file != null -> File(dir, p.file).takeIf { it.isFile }?.readBytes() ?: continue
                else -> continue
            }
            val name = p.name ?: if (p.text != null) "text_$i.txt" else "part_$i"
            body.addPart(PduPart().apply {
                if (p.mime.startsWith("text")) setCharset(CharacterSets.UTF_8)
                setContentType(p.mime.toByteArray())
                setContentLocation(name.toByteArray())
                setContentId(name.substringBeforeLast('.').toByteArray())
                setData(data)
            })
        }
        if (body.partsNum == 0) return false

        val pdu: MultimediaMessagePdu
        val target: Uri
        if (e.box == Telephony.Mms.MESSAGE_BOX_INBOX) {
            pdu = RetrieveConf().apply {
                e.from?.let { setFrom(EncodedStringValue(it)) }
                e.to.forEach { addTo(EncodedStringValue(it)) }
                e.cc.forEach { addCc(EncodedStringValue(it)) }
                setContentType(ContentType.MULTIPART_RELATED.toByteArray())
            }
            target = Telephony.Mms.Inbox.CONTENT_URI
        } else {
            pdu = SendReq().apply {
                e.to.forEach { addTo(EncodedStringValue(it)) }
                e.cc.forEach { addCc(EncodedStringValue(it)) }
            }
            target = Telephony.Mms.Sent.CONTENT_URI
        }
        pdu.setDate(dateSeconds)
        e.subject?.takeIf { it.isNotEmpty() }?.let { pdu.setSubject(EncodedStringValue(it)) }
        pdu.setBody(body)

        val uri = PduPersister.getPduPersister(context).persist(pdu, target, true, true, null, -1) ?: return false
        context.contentResolver.update(uri, ContentValues().apply {
            put(Telephony.Mms.DATE, dateSeconds)
            put(Telephony.Mms.READ, if (e.read) 1 else 0)
            put(Telephony.Mms.SEEN, 1)
            put(Telephony.Mms.MESSAGE_BOX, e.box)
        }, null, null)
        return true
    }
}
