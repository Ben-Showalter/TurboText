package com.turbotext.app

import android.util.Log
import com.google.android.mms.pdu_alt.CharacterSets
import com.google.android.mms.pdu_alt.EncodedStringValue
import com.google.android.mms.pdu_alt.MultimediaMessagePdu
import com.google.android.mms.pdu_alt.PduBody
import com.google.android.mms.pdu_alt.PduParser
import com.google.android.mms.pdu_alt.PduPart
import com.google.android.mms.pdu_alt.RetrieveConf

private const val TAG = "TurboTextMms"

/**
 * Pulls the content (caption text, image / vCard attachments, addresses)
 * out of a downloaded M-Retrieve.conf PDU — the payload of a received MMS.
 *
 * PRIMARY PATH — [parseStructured]: the spec-compliant WAP / OMA-MMS
 * parser bundled inside com.klinkerapps:android-smsmms
 * (com.google.android.mms.pdu_alt.*). That code is the AOSP MMS parser
 * that Google Messages and Samsung Messages descend from. It reads the
 * PDU's real structure — typed headers, an explicit multipart body, and
 * each part's own content-type, charset and content-transfer-encoding
 * (base64 / quoted-printable are decoded for us before we see the bytes).
 * A UTF-16 caption, an ISO-8859 caption, or a GIF / WebP / HEIC image now
 * come out correct instead of as a run of nonsense characters or a bare
 * "Picture message".
 *
 * FALLBACK PATH — [extractHeuristic]: if the structured parser rejects
 * the PDU (missing mandatory header, genuinely malformed carrier output),
 * the older forgiving byte-scan runs — look for JPEG/PNG magic numbers
 * and any embedded readable ASCII, and take whatever's found. It's lossy,
 * but better than dropping the message on the floor.
 */
object MmsRetrieveParser {

    data class ExtractedContent(
        val text: String,
        val imageBytes: ByteArray?,
        val vcardBytes: ByteArray?,
        val senderAddress: String?,
        /** Every address found in the PDU (From, then To, then Cc). More
         *  than one number here (beyond our own, which the caller filters
         *  out) means a group MMS. */
        val allAddresses: List<String>,
        /** Raw bytes of an audio part (voice message), if present. */
        val audioBytes: ByteArray? = null,
        /** The audio part's own MIME type (e.g. "audio/amr", "audio/3gpp",
         *  "audio/mp4") — carried through so the part is stored with the
         *  right container type and the player can decode it. */
        val audioContentType: String? = null
    )

    fun extract(data: ByteArray): ExtractedContent {
        val structured = parseStructured(data)
        if (structured != null) {
            Log.i(
                TAG,
                "MmsRetrieveParser: structured parse ok (textLen=${structured.text.length}, " +
                    "image=${structured.imageBytes != null}, vcard=${structured.vcardBytes != null}, " +
                    "audio=${structured.audioBytes != null}, addrs=${structured.allAddresses.size})"
            )
            return structured
        }
        Log.w(TAG, "MmsRetrieveParser: structured parse unavailable, using heuristic fallback")
        return extractHeuristic(data)
    }

    // ---------------------------------------------------------------------
    //  Structured (spec-compliant) path
    // ---------------------------------------------------------------------

    private fun parseStructured(data: ByteArray): ExtractedContent? {
        val pdu = try {
            // parseContentDisposition = true: honour Content-Disposition so
            // "attachment" vs "inline" parts and their filenames are read.
            PduParser(data, true).parse()
        } catch (e: Exception) {
            Log.w(TAG, "PduParser threw on ${data.size}-byte PDU", e)
            null
        }
        if (pdu == null) {
            Log.w(TAG, "PduParser returned null (mandatory header missing or malformed PDU)")
            return null
        }
        if (pdu !is MultimediaMessagePdu) {
            Log.w(TAG, "parsed PDU is ${pdu.javaClass.simpleName}, not a retrieve body")
            return null
        }

        val body: PduBody = pdu.body ?: run {
            Log.w(TAG, "parsed RetrieveConf has no body")
            return null
        }

        val textParts = mutableListOf<String>()
        var imageBytes: ByteArray? = null
        var vcardBytes: ByteArray? = null
        var audioBytes: ByteArray? = null
        var audioContentType: String? = null

        for (i in 0 until body.partsNum) {
            val part = body.getPart(i) ?: continue
            val ct = contentType(part)
            when {
                ct.endsWith("/smil") || ct == "application/smil" -> {
                    // Layout markup describing how the parts are arranged
                    // on screen (timing, position) — never message content.
                }
                ct == "text/x-vcard" || ct == "text/vcard" || ct == "text/directory" -> {
                    if (vcardBytes == null) vcardBytes = part.data
                }
                ct.startsWith("image/") -> {
                    if (imageBytes == null) imageBytes = part.data
                }
                ct.startsWith("audio/") -> {
                    // Voice message. Carriers most often use AMR in a 3GPP
                    // container ("audio/amr" / "audio/3gpp"), but iPhones
                    // send "audio/mp4" (.m4a/AAC) — keep the declared type
                    // so the part is stored and decoded correctly.
                    if (audioBytes == null) {
                        audioBytes = part.data
                        audioContentType = ct
                    }
                }
                ct.startsWith("text/") -> {
                    decodePartText(part)?.trim()?.takeIf { it.isNotEmpty() }?.let { textParts.add(it) }
                }
                // video/*, application/pdf, … — real attachments this app
                // doesn't surface yet. Skip quietly rather than letting
                // their bytes fall through to a text scan.
            }
        }

        val text = textParts.joinToString("\n").trim()
        val addresses = collectAddresses(pdu)

        // Parsed cleanly but yielded nothing usable — more suspect than
        // useful, so let the heuristic have a go at the raw bytes.
        if (text.isEmpty() && imageBytes == null && vcardBytes == null &&
            audioBytes == null && addresses.isEmpty()
        ) {
            Log.w(TAG, "structured parse produced no text, no attachment and no address — falling back")
            return null
        }

        return ExtractedContent(
            text = text,
            imageBytes = imageBytes,
            vcardBytes = vcardBytes,
            audioBytes = audioBytes,
            audioContentType = audioContentType,
            senderAddress = addresses.firstOrNull(),
            allAddresses = addresses
        )
    }

    private fun contentType(part: PduPart): String {
        val raw = part.contentType ?: return ""
        // "ct" can carry parameters ("text/plain; charset=utf-8") and vary
        // in case — normalise before matching.
        return String(raw, Charsets.US_ASCII).substringBefore(';').trim().lowercase()
    }

    /** Decodes a text part with the charset declared on the part itself.
     *  AOSP's [EncodedStringValue] maps the MIBenum charset code (UTF-8,
     *  UTF-16/UCS-2, ISO-8859-*, Shift-JIS, …) to a real decoder and
     *  falls back to ISO-8859-1 then the platform default. This is the
     *  fix for captions that used to arrive as mojibake — they were
     *  non-UTF-8 text being force-decoded as UTF-8. */
    private fun decodePartText(part: PduPart): String? {
        val bytes = part.data ?: return null
        if (bytes.isEmpty()) return null
        val charset = part.charset.takeIf { it != 0 } ?: CharacterSets.UTF_8
        return try {
            EncodedStringValue(charset, bytes).string
        } catch (e: Exception) {
            Log.w(TAG, "text part charset=$charset decode failed, using UTF-8", e)
            String(bytes, Charsets.UTF_8)
        }
    }

    /** From + To + Cc, in that order, de-duplicated with insertion order
     *  preserved. Same set the old byte-scan returned (every /TYPE=PLMN
     *  address in the PDU), so downstream group-thread detection —
     *  including its own filtering-out of our number — behaves the same. */
    private fun collectAddresses(pdu: MultimediaMessagePdu): List<String> {
        val out = LinkedHashSet<String>()
        fun add(value: EncodedStringValue?) {
            val s = value?.string?.let(::stripAddressType)?.trim().orEmpty()
            // "insert-address-token" is the placeholder AOSP uses for the
            // local device in a draft — never a real participant.
            if (s.isNotEmpty() && !s.equals("insert-address-token", ignoreCase = true)) out.add(s)
        }
        add(pdu.from)
        pdu.to?.forEach { add(it) }
        if (pdu is RetrieveConf) pdu.cc?.forEach { add(it) }
        return out.toList()
    }

    /** MMS addresses arrive tagged: "15551234567/TYPE=PLMN" for a phone
     *  number, "/TYPE=IPV4" etc. for IP/email routing. Downstream wants
     *  the bare number or email. */
    private fun stripAddressType(addr: String): String = addr.substringBefore("/TYPE=")

    // ---------------------------------------------------------------------
    //  Heuristic fallback path (used only when the structured parser bails)
    // ---------------------------------------------------------------------

    private fun extractHeuristic(data: ByteArray): ExtractedContent {
        val imageBytes = extractImage(data)
        val vcardBytes = extractVcard(data)
        val text = extractReadableText(data, imageBytes, vcardBytes)
        val allAddresses = extractAllAddresses(data)
        val sender = allAddresses.firstOrNull()
        // No audio here — a voice part can't be pulled out of raw bytes by
        // signature-scanning the way JPEG/PNG/vCard can.
        return ExtractedContent(
            text = text,
            imageBytes = imageBytes,
            vcardBytes = vcardBytes,
            senderAddress = sender,
            allAddresses = allAddresses
        )
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
            // General filename shape (word.ext, or word.word.ext for the
            // auto-numbered names MMS parts commonly get, e.g. carriers'
            // default "text.000000.txt"/"image.000001.jpg") — this has
            // now caught smil.xml, a raw transaction ID, "text.txt", and
            // "text.000000.txt" (all a part's own filename metadata, not
            // its content) as four separate specific bugs. A generic
            // "dot-separated tokens, short final extension" rule should
            // catch this whole class rather than needing another patch
            // each time a new one turns up. (An earlier version of this
            // regex only allowed exactly one dot, which matched "text.txt"
            // but not the two-dot "text.000000.txt" shape — letting that
            // one through as if it were real caption text.)
            Regex("""^[\w-]{1,40}(\.[\w-]{1,40})*\.[A-Za-z0-9]{2,4}$""").matches(s) ||
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
