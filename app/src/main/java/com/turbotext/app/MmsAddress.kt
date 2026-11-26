package com.turbotext.app

/**
 * Picture-message recipients in the form the carrier can resolve. The
 * MMS library only marks a recipient as a phone number (/TYPE=PLMN) when
 * it's just digits, "+", "-" and "."; a number written "+1 269-359-3836"
 * or "(269) 359-3836" went out as an unknown address, and Verizon refused
 * the picture with 0x84 "address unresolved". The same contact's address
 * is written differently depending on how the conversation was opened,
 * which is why sending to them sometimes worked and sometimes didn't.
 */
object MmsAddress {

    /** [address] as "+digits" when it's a phone number (10-digit US numbers
     *  get "+1"); emails and short codes are left as they are. */
    fun clean(address: String): String {
        val trimmed = address.trim()
        if (trimmed.contains('@')) return trimmed
        if (trimmed.any { it.isLetter() }) return trimmed
        val digits = trimmed.filter(Char::isDigit)
        if (digits.isEmpty()) return trimmed
        return when {
            digits.length == 10 -> "+1$digits"
            digits.length == 11 && digits.startsWith("1") -> "+$digits"
            digits.length <= 6 -> digits // short code
            trimmed.startsWith("+") -> "+$digits"
            else -> digits
        }
    }

    /** For the log (no number): "phone", "email" or "other", plus whether
     *  spaces or brackets had to be removed. */
    fun describe(original: String, cleaned: String): String {
        val kind = when {
            cleaned.contains('@') -> "email"
            cleaned.trimStart('+').all(Char::isDigit) -> "phone"
            else -> "other"
        }
        return if (original.trim() != cleaned) "$kind, cleaned" else kind
    }
}
