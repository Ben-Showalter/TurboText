package com.turbotext.app

import android.util.JsonReader
import android.util.JsonWriter
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.StringReader
import java.io.StringWriter

class ArchiveFormatTest {

    @Test
    fun textsAndPictureMessagesRoundTrip() {
        val sms = ArchiveFormat.Entry(
            kind = "sms", box = 2, date = 1_793_000_000_123, read = true, thread = 12,
            address = "+15551234567", dateSent = 1_793_000_000_000, body = "Sunday \"dinner\"?\nYes"
        )
        val mms = ArchiveFormat.Entry(
            kind = "mms", box = 1, date = 1_793_000_100_000, read = false, thread = 30,
            from = "+15552223333", to = listOf("+15551234567", "+15554445555"), cc = emptyList(), subject = "pics",
            parts = listOf(
                ArchiveFormat.Part("text/plain", text = "look"),
                ArchiveFormat.Part("image/jpeg", name = "IMG 1.jpg", file = "media/9-1-IMG 1.jpg")
            )
        )
        val out = StringWriter()
        JsonWriter(out).use { w ->
            ArchiveFormat.writeHeader(w, created = 42, count = 2)
            ArchiveFormat.write(w, sms)
            ArchiveFormat.write(w, mms)
            ArchiveFormat.writeFooter(w)
        }
        val read = ArrayList<ArchiveFormat.Entry>()
        var header: ArchiveFormat.Header? = null
        JsonReader(StringReader(out.toString())).use { r ->
            ArchiveFormat.read(r, onHeader = { header = it }) { read.add(it) }
        }
        assertEquals(ArchiveFormat.Header(ArchiveFormat.VERSION, 42, 2), header)
        assertEquals(listOf(sms, mms), read)
        assertEquals("look", read[1].text)
        assertEquals(true, read[0].isOutgoing)
        assertEquals(false, read[1].isOutgoing)
        // Listing archives reads only the header.
        assertEquals(ArchiveFormat.Header(ArchiveFormat.VERSION, 42, 2), JsonReader(StringReader(out.toString())).use { ArchiveFormat.readHeader(it) })
    }

    @Test
    fun fileNamesAreSafe() {
        assertEquals("Mom _ Dad", ArchiveManager.safeFileName("Mom & Dad"))
        assertEquals("conversation", ArchiveManager.safeFileName(""))
    }
}
