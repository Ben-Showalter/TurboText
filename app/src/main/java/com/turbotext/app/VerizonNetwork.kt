package com.turbotext.app

/**
 * Verizon's picture-message server identifies the sender by HTTP headers
 * carrying their number (X-VzW-MDN). Without them, and with no number in
 * the message's From, it refused pictures with 0x84 "sender address
 * unresolved". US Mobile's SIM has no mms_config entry with these headers,
 * so TurboText adds them itself, as the old klinker library did on Verizon.
 */
object VerizonNetwork {
    private val MCC_MNC = setOf(
        "310004", "310010", "310012", "310013",
        "311480", "311481", "311482", "311483", "311484",
        "311485", "311486", "311487", "311488", "311489"
    )

    /** True for a Verizon SIM or network (MVNOs like US Mobile included). */
    fun isVerizon(simMccMnc: String?, networkMccMnc: String?, networkName: String?): Boolean =
        simMccMnc?.filter(Char::isDigit) in MCC_MNC ||
            networkMccMnc?.filter(Char::isDigit) in MCC_MNC ||
            networkName?.contains("verizon", ignoreCase = true) == true

    /** The 10-digit form of [number], or null if it isn't a US number. */
    fun tenDigits(number: String): String? {
        var d = number.filter(Char::isDigit)
        if (d.length == 11 && d.startsWith("1")) d = d.substring(1)
        return if (d.length == 10) d else null
    }

    /** The headers Verizon's server reads the sender from. */
    fun httpParamsFor(number: String): String? {
        val ten = tenDigits(number) ?: return null
        return "x-up-calling-line-id: 1$ten|X-VzW-MDN: 1$ten"
    }

    /** Our number as the message's From: "+1" and 10 digits when it's a US number. */
    fun fromAddress(number: String): String = tenDigits(number)?.let { "+1$it" } ?: number.trim()
}
