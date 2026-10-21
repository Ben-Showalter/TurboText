package com.turbotext.app

/** What a text's delivery report says, from its PDU status. */
object DeliveryStatus {
    enum class Result { DELIVERED, PENDING, FAILED }

    /**
     * [status] is SmsMessage.getStatus() of the report; [is3gpp2] when the
     * report came in CDMA format (the broadcast's "format" extra).
     *
     * GSM (TP-Status): below 0x20 the message was delivered — 0x01 and
     * 0x02 also mean delivered, they used to show as failed; 0x20-0x3F
     * the network is still trying; 0x40 and up it gave up.
     * CDMA: the error class in bits 24-25 — 0 delivered, 2 still trying,
     * anything else failed.
     */
    fun classify(status: Int, is3gpp2: Boolean): Result {
        if (is3gpp2) {
            return when ((status shr 24) and 0x03) {
                0 -> Result.DELIVERED
                2 -> Result.PENDING
                else -> Result.FAILED
            }
        }
        return when {
            status < 0x20 -> Result.DELIVERED
            status < 0x40 -> Result.PENDING
            else -> Result.FAILED
        }
    }
}
