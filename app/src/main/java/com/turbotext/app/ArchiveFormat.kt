package com.turbotext.app

import android.util.JsonReader
import android.util.JsonToken
import android.util.JsonWriter

/**
 * The exact, importable copy of an archive (archive.json): every text and
 * picture message as the phone stored it — numbers, dates, which box,
 * read or not, and each picture message's parts (text, or a file under
 * media/). Written and read as a stream, so a long history never has to
 * fit in memory at once.
 */
object ArchiveFormat {
    const val VERSION = 1

    /** One part of a picture message: its text, or a file in media/. */
    data class Part(val mime: String, val text: String? = null, val name: String? = null, val file: String? = null)

    /** A text (kind "sms") or picture message (kind "mms").
     *  SMS: [box] is its TYPE, [address] the other person, [date] ms.
     *  MMS: [box] is its MESSAGE_BOX, [from]/[to]/[cc] its addresses,
     *  [date] ms (stored by the phone in seconds). */
    data class Entry(
        val kind: String,
        val box: Int,
        val date: Long,
        val read: Boolean,
        val thread: Long,
        val address: String? = null,
        val dateSent: Long = 0,
        val body: String? = null,
        val from: String? = null,
        val to: List<String> = emptyList(),
        val cc: List<String> = emptyList(),
        val subject: String? = null,
        val parts: List<Part> = emptyList()
    ) {
        val isMms get() = kind == "mms"
        /** Inbox is box 1 for both texts and picture messages. */
        val isOutgoing get() = box != 1
        /** What the message says: an SMS body, or its picture message's text parts. */
        val text: String get() = if (isMms) parts.mapNotNull { it.text }.joinToString("\n") else body.orEmpty()
    }

    fun writeHeader(w: JsonWriter, created: Long, count: Int) {
        w.beginObject()
        w.name("format").value(VERSION.toLong())
        w.name("created").value(created)
        w.name("count").value(count.toLong())
        w.name("messages").beginArray()
    }

    fun writeFooter(w: JsonWriter) {
        w.endArray()
        w.endObject()
    }

    fun write(w: JsonWriter, e: Entry) {
        w.beginObject()
        w.name("kind").value(e.kind)
        w.name("box").value(e.box.toLong())
        w.name("date").value(e.date)
        w.name("read").value(e.read)
        w.name("thread").value(e.thread)
        if (e.isMms) {
            e.from?.let { w.name("from").value(it) }
            w.name("to").beginArray(); e.to.forEach { w.value(it) }; w.endArray()
            w.name("cc").beginArray(); e.cc.forEach { w.value(it) }; w.endArray()
            e.subject?.let { w.name("subject").value(it) }
            w.name("parts").beginArray()
            for (p in e.parts) {
                w.beginObject()
                w.name("mime").value(p.mime)
                p.text?.let { w.name("text").value(it) }
                p.name?.let { w.name("name").value(it) }
                p.file?.let { w.name("file").value(it) }
                w.endObject()
            }
            w.endArray()
        } else {
            w.name("address").value(e.address.orEmpty())
            w.name("dateSent").value(e.dateSent)
            w.name("body").value(e.body.orEmpty())
        }
        w.endObject()
    }

    /** Header fields of an archive: (format, created, count). */
    data class Header(val format: Int, val created: Long, val count: Int)

    /**
     * Reads an archive: the header, then [onEntry] for each message in
     * order. Unknown fields are skipped, so later versions stay readable.
     */
    fun read(r: JsonReader, onHeader: (Header) -> Unit = {}, onEntry: (Entry) -> Unit) {
        var format = 0
        var created = 0L
        var count = 0
        r.beginObject()
        while (r.hasNext()) {
            when (r.nextName()) {
                "format" -> format = r.nextInt()
                "created" -> created = r.nextLong()
                "count" -> count = r.nextInt()
                "messages" -> {
                    onHeader(Header(format, created, count))
                    r.beginArray()
                    while (r.hasNext()) onEntry(readEntry(r))
                    r.endArray()
                }
                else -> r.skipValue()
            }
        }
        r.endObject()
    }

    /** Only the header, for listing archives without reading them through. */
    fun readHeader(r: JsonReader): Header {
        var format = 0
        var created = 0L
        var count = 0
        r.beginObject()
        while (r.hasNext()) {
            when (r.nextName()) {
                "format" -> format = r.nextInt()
                "created" -> created = r.nextLong()
                "count" -> count = r.nextInt()
                "messages" -> return Header(format, created, count)
                else -> r.skipValue()
            }
        }
        return Header(format, created, count)
    }

    private fun readEntry(r: JsonReader): Entry {
        var kind = "sms"; var box = 1; var date = 0L; var read = true; var thread = 0L
        var address: String? = null; var dateSent = 0L; var body: String? = null
        var from: String? = null; var subject: String? = null
        val to = ArrayList<String>(); val cc = ArrayList<String>(); val parts = ArrayList<Part>()
        r.beginObject()
        while (r.hasNext()) {
            when (r.nextName()) {
                "kind" -> kind = r.nextString()
                "box" -> box = r.nextInt()
                "date" -> date = r.nextLong()
                "read" -> read = r.nextBoolean()
                "thread" -> thread = r.nextLong()
                "address" -> address = r.nextString()
                "dateSent" -> dateSent = r.nextLong()
                "body" -> body = r.nextString()
                "from" -> from = r.nextString()
                "subject" -> subject = r.nextString()
                "to" -> readStrings(r, to)
                "cc" -> readStrings(r, cc)
                "parts" -> {
                    r.beginArray()
                    while (r.hasNext()) {
                        var mime = ""; var text: String? = null; var name: String? = null; var file: String? = null
                        r.beginObject()
                        while (r.hasNext()) {
                            when (r.nextName()) {
                                "mime" -> mime = r.nextString()
                                "text" -> text = r.nextString()
                                "name" -> name = r.nextString()
                                "file" -> file = r.nextString()
                                else -> r.skipValue()
                            }
                        }
                        r.endObject()
                        parts.add(Part(mime, text, name, file))
                    }
                    r.endArray()
                }
                else -> r.skipValue()
            }
        }
        r.endObject()
        return Entry(kind, box, date, read, thread, address, dateSent, body, from, to, cc, subject, parts)
    }

    private fun readStrings(r: JsonReader, into: MutableList<String>) {
        if (r.peek() == JsonToken.NULL) { r.nextNull(); return }
        r.beginArray()
        while (r.hasNext()) into.add(r.nextString())
        r.endArray()
    }
}
