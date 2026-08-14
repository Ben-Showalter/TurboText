package com.turbotext.app

/**
 * A full M-Retrieve.conf parser (the PDU containing an MMS's actual
 * content) is a MIME-multipart-like structure — properly parsing every
 * part boundary and content-type per spec is a much bigger undertaking
 * than the notification parser above. Rather than attempt that blind,
 * this takes a more forgiving, heuristic approach: scan the downloaded
 * bytes for known image file signatures (JPEG/PNG magic numbers) and any
 * embedded readable text, and extract whatever's found. This won't
 * losslessly handle every possible MMS structure a carrier might send,
 * but it has a real chance of correctly pulling out the common case (one
 * photo, optionally with a text caption) without the risk of a
 * byte-perfect-but-untested full parser silently mis-parsing something.
 */
object MmsRetrieveParser {

    data class ExtractedContent(
        val text: String,
        val imageBytes: ByteArray?,
        val vcardBytes: ByteArray?,
        val senderAddress: String?,
        /** Every phone number found in the PDU via the same /TYPE=PLMN
         *  address encoding used for the sender — since From, To, and Cc
         *  are all encoded the same way, this doubles as the full
         *  participant list for detecting group MMS. More than one
         *  distinct number here (beyond the sender) means a group. */
        val allAddresses: List<String>
    )

    fun extract(data: ByteArray): ExtractedContent {
        val imageBytes = extractImage(data)
        val vcardBytes = extractVcard(data)
        val text = extractReadableText(data, imageBytes, vcardBytes)
        val allAddresses = extractAllAddresses(data)
        val sender = allAddresses.firstOrNull()
        return ExtractedContent(text, imageBytes, vcardBytes, sender, allAddresses)
    }

    /** A vCard part is plain text (BEGIN:VCARD ... END:VCARD per spec),
     *  so without this it would otherwise get swept up by the general
     *  caption-text scan below — pulling it out explicitly means it's
     *  handled as its own attachment instead of ending up as message
     *  text. */
    private fun extractVcard(data: ByteArray): ByteArray? {
        val start = indexOfIgnoreCase(data, "BEGIN:VCARD", 0)
        if (start < 0) return null
        val endPos = indexOfIgnoreCase(data, "END:VCARD", start)
        // A missing END marker (truncated download) still means everything
        // from here on is vcard content, not a caption — better to grab
        // the rest of the buffer than let a fragment slip past the
        // exclusion range below and get picked up as readable text.
        val actualEnd = if (endPos >= 0) (endPos + "END:VCARD".length).coerceAtMost(data.size) else data.size
        return data.copyOfRange(start, actualEnd)
    }

    /** Case-insensitive byte search for an ASCII needle — some senders emit
     *  lowercase/mixed-case vCard markers, which the original exact-match
     *  search silently missed. */
    private fun indexOfIgnoreCase(haystack: ByteArray, needleAscii: String, from: Int): Int {
        val needle = needleAscii.uppercase().toByteArray(Charsets.US_ASCII)
        if (from < 0 || haystack.size < needle.size) return -1
        outer@ for (i in from..(haystack.size - needle.size)) {
            for (j in needle.indices) {
                val hb = haystack[i + j].toInt().and(0xFF).toChar().uppercaseChar().code.toByte()
                if (hb != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }

    /** MMS notifications don't reliably include the sender's address until
     *  the actual content is downloaded, and even then it's often encoded
     *  in a form not worth fully parsing here — this just looks for
     *  something phone-number-shaped in the raw bytes as a best effort. */
    /** MMS notifications don't reliably include the sender's address until
     *  the actual content is downloaded, and even then it's often encoded
     *  in a form not worth fully parsing here — this looks specifically
     *  for the "<number>/TYPE=PLMN" pattern real MMS addresses use (this
     *  exact pattern showed up in an earlier real trace of this app's
     *  MMS traffic). A bare digit-run regex was tried first but had to be
     *  dropped — it matched a coincidental 10-digit substring inside the
     *  transaction ID and filed a real message into a bogus new thread. */
    private fun extractAllAddresses(data: ByteArray): List<String> {
        val ascii = String(data, Charsets.US_ASCII).filter { it.code in 32..126 }
        // Exactly 10 digits first — a bare {7,15} range is greedy and
        // swallowed extra unrelated digits sitting right before the real
        // number in one real test. Anchoring on the standard US length
        // avoids that; the looser pattern is a fallback for numbers that
        // genuinely aren't 10 digits (international, etc.).
        val exact = Regex("""(\d{10})/TYPE=PLMN""").findAll(ascii).map { it.groupValues[1] }.distinct().toList()
        if (exact.isNotEmpty()) return exact
        return Regex("""(\+?\d{7,15})/TYPE=PLMN""").findAll(ascii).map { it.groupValues[1] }.distinct().toList()
    }

    private fun extractImage(data: ByteArray): ByteArray? {
        // JPEG: starts with FF D8 FF, ends with FF D9.
        val jpegStart = indexOf(data, byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()), 0)
        if (jpegStart >= 0) {
            val jpegEnd = lastIndexOf(data, byteArrayOf(0xFF.toByte(), 0xD9.toByte()), jpegStart)
            if (jpegEnd > jpegStart) {
                return data.copyOfRange(jpegStart, jpegEnd + 2)
            }
        }
        // PNG: starts with the 8-byte PNG signature, ends with the IEND chunk.
        val pngSignature = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
        )
        val pngStart = indexOf(data, pngSignature, 0)
        if (pngStart >= 0) {
            val iend = byteArrayOf('I'.code.toByte(), 'E'.code.toByte(), 'N'.code.toByte(), 'D'.code.toByte())
            val iendPos = indexOf(data, iend, pngStart)
            if (iendPos > pngStart) {
                val end = (iendPos + iend.size + 4).coerceAtMost(data.size)
                return data.copyOfRange(pngStart, end)
            }
        }
        return null
    }

    private fun extractReadableText(data: ByteArray, imageBytes: ByteArray?, vcardBytes: ByteArray?): String {
        val imageRange = imageBytes?.let {
            val start = indexOf(data, it.copyOfRange(0, minOf(8, it.size)), 0)
            if (start >= 0) start until (start + it.size) else null
        }
        val vcardRange = vcardBytes?.let {
            val start = indexOf(data, it.copyOfRange(0, minOf(8, it.size)), 0)
            if (start >= 0) start until (start + it.size) else null
        }
        fun inExcludedRange(i: Int) = (imageRange != null && i in imageRange) || (vcardRange != null && i in vcardRange)

        var bestRun = ""
        var i = 0
        while (i < data.size) {
            if (inExcludedRange(i)) {
                i = maxOf(imageRange?.let { if (i in it) it.last + 1 else -1 } ?: -1,
                    vcardRange?.let { if (i in it) it.last + 1 else -1 } ?: -1)
                continue
            }
            if (isPrintable(data[i])) {
                val start = i
                while (i < data.size && !inExcludedRange(i) && isPrintable(data[i])) i++
                val run = String(data, start, i - start, Charsets.UTF_8).trim()
                if (run.length in 3..500 && run.length > bestRun.length &&
                    looksLikeWords(run) && !looksLikeMarkup(run)
                ) {
                    bestRun = run
                }
            } else {
                i++
            }
        }
        return bestRun
    }

    private fun isPrintable(b: Byte): Boolean {
        val v = b.toInt() and 0xFF
        return v in 32..126 || v == 10 || v == 13
    }

    private fun looksLikeWords(s: String): Boolean {
        val letters = s.count { it.isLetter() }
        return letters >= s.length / 3
    }

    /** Every MMS includes a SMIL block describing how to lay out its
     *  parts (timing, positioning) — it's technical markup, not a
     *  message, and shouldn't be mistaken for a caption. */
    private fun looksLikeMarkup(s: String): Boolean {
        return s.contains("<smil") || s.contains("</") || s.contains("<?xml") ||
            s.contains("/TYPE=PLMN") || s.startsWith("application/") || s.startsWith("image/") ||
            s.startsWith("text/") || s.contains("multipart/") ||
            s.equals("smil.xml", ignoreCase = true) ||
            Regex("""\.(jpg|jpeg|png|gif|xml|smil)$""", RegexOption.IGNORE_CASE).containsMatchIn(s) ||
            // A transaction ID or similar hex identifier is heavy on
            // A-F letters purely from being hex-encoded, which was
            // enough to slip past the letter-ratio check in
            // looksLikeWords by coincidence — real captions don't look
            // like pure hex strings.
            Regex("""^[0-9A-Fa-f]{8,}$""").matches(s) ||
            // General filename shape (word.ext) — this has now caught
            // smil.xml, a raw transaction ID, and text.txt (a part's own
            // filename metadata, not its content) as three separate
            // specific bugs. A generic "single token, dot, short
            // extension" rule should catch this whole class rather than
            // needing another patch each time a new one turns up.
            Regex("""^[\w-]{1,40}\.[A-Za-z0-9]{2,4}$""").matches(s) ||
            (s.count { it == '<' } + s.count { it == '>' }) > s.length / 10
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray, from: Int): Int {
        if (needle.isEmpty() || from < 0 || haystack.size < needle.size) return -1
        outer@ for (i in from..(haystack.size - needle.size)) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }

    private fun lastIndexOf(haystack: ByteArray, needle: ByteArray, from: Int): Int {
        if (needle.isEmpty() || haystack.size < needle.size) return -1
        for (i in (haystack.size - needle.size) downTo from) {
            var match = true
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) { match = false; break }
            }
            if (match) return i
        }
        return -1
    }
}
