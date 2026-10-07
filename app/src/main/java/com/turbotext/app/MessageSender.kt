package com.turbotext.app

import android.content.Context
import android.util.Log

/**
 * The one place that decides how a message goes out (modeled on DPAD
 * Messaging's SendingRouter):
 *
 * - several recipients → one group MMS, so everyone shares the thread
 * - any attachment → MMS
 * - an email address → MMS (plain SMS can't reach one)
 * - otherwise → SMS (split into parts automatically when long)
 *
 * Attachments are resized / re-encoded to fit the carrier's size limit
 * first (see [AttachmentPolicy]).
 */
object MessageSender {

    private const val TAG = "TurboTextSend"

    enum class Route { SMS, MMS_SINGLE, MMS_GROUP }

    fun routeFor(recipients: List<String>, attachment: OutgoingAttachment?, isEmail: (String) -> Boolean): Route = when {
        recipients.size > 1 -> Route.MMS_GROUP
        attachment != null -> Route.MMS_SINGLE
        recipients.firstOrNull()?.let(isEmail) == true -> Route.MMS_SINGLE
        else -> Route.SMS
    }

    /**
     * Sends [body] (and [attachment], if any) to [recipients]. Blocks —
     * video compression can take a minute — so call from a background
     * thread. [onStatus] gets short progress text for the UI, e.g.
     * "Compressing video… 40%". Returns null on success, or a message to
     * show the user if it couldn't be sent.
     */
    fun send(
        context: Context,
        recipients: List<String>,
        body: String,
        attachment: OutgoingAttachment?,
        onStatus: (String) -> Unit = {}
    ): String? {
        val repo = SmsRepository(context)
        val cleaned = recipients.map { it.trim() }.filter { it.isNotEmpty() }
        if (cleaned.isEmpty()) return "No recipient"

        val route = routeFor(cleaned, attachment) { repo.isEmailAddress(it) }
        Log.i(TAG, "sending via $route to ${cleaned.size} recipient(s), attachment=${attachment?.mime}")

        if (route == Route.SMS) {
            repo.sendMessage(cleaned[0], body)
            return null
        }

        val parts = if (attachment != null) {
            try {
                if (attachment.isVideo) onStatus("Compressing video…")
                val part = AttachmentPolicy.prepare(context, attachment, AttachmentPolicy.budgetFor(body)) { pct ->
                    onStatus("Compressing video… $pct%")
                }
                listOf(part)
            } catch (e: AttachmentPolicy.TooLargeException) {
                return e.message ?: "Attachment too large"
            } catch (e: Exception) {
                Log.e(TAG, "couldn't prepare attachment", e)
                return "Couldn't prepare the attachment: ${e.message}"
            }
        } else {
            emptyList()
        }

        // Group recipients go out in one consistent format — a test showed
        // "(555) 491-8332" and "+1 555-535-6558" in the same send, and only
        // one of them being handled correctly.
        val to = if (route == Route.MMS_GROUP) cleaned.map { repo.normalizePhoneNumber(it) } else cleaned
        onStatus("Sending…")
        val uri = MmsTransmitter.send(context, to, body, parts, groupMms = route == Route.MMS_GROUP)
        ProviderChangeTracker.bump()
        return if (uri == null) "Couldn't send the multimedia message" else null
    }
}
