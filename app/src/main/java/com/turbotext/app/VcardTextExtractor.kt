package com.turbotext.app

/** Some carriers/phones deliver a small vCard as a plain concatenated SMS
 *  (text/plain) rather than an MMS attachment, so it never reaches the
 *  MMS-part vCard detection in SmsRepository.readMmsParts() /
 *  MmsRetrieveParser — it would otherwise just sit in Telephony.Sms.BODY
 *  as raw vCard syntax, which reads like garbled text (e.g. "N:Doe;John;;;").
 *  This finds that same BEGIN:VCARD ... END:VCARD block inside a plain SMS
 *  body string so it can be handled the same way as an MMS vCard part. */
object VcardTextExtractor {
    private val beginRegex = Regex("BEGIN:VCARD", RegexOption.IGNORE_CASE)
    private val endRegex = Regex("END:VCARD", RegexOption.IGNORE_CASE)

    /** Returns the vCard block (BEGIN:VCARD..END:VCARD, inclusive) if
     *  found, else null. Tolerates a missing END marker (truncated
     *  delivery) by taking the rest of the body, rather than leaving the
     *  fragment to fall through as regular message text. */
    fun extract(body: String): String? {
        val beginMatch = beginRegex.find(body) ?: return null
        val start = beginMatch.range.first
        val endMatch = endRegex.find(body, start)
        val end = if (endMatch != null) endMatch.range.last + 1 else body.length
        return body.substring(start, end)
    }
}