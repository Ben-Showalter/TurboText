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
 * first (see [AttachmentPolicy]). With several, the limit is split evenly
 * between them so the whole message fits.
 */
object MessageSender {

    private const val TAG = "TurboTextSend"

    enum class Route { SMS, MMS_SINGLE, MMS_GROUP }

    fun routeFor(recipients: List<String>, hasAttachments: Boolean, isEmail: (String) -> Boolean): Route = when {
        recipients.size > 1 -> Route.MMS_GROUP
        hasAttachments -> Route.MMS_SINGLE
        recipients.firstOrNull()?.let(isEmail) == true -> Route.MMS_SINGLE
        else -> Route.SMS
    }

    /**
     * Sends [body] (and [attachments], if any) to [recipients]. Blocks —
     * video compression can take a minute — so call from a background
     * thread. [onStatus] gets short progress text for the UI, e.g.
     * "Compressing video… 40%". Returns null on success, or a message to
     * show the user if it couldn't be sent.
     */
    fun send(
        context: Context,
        recipients: List<String>,
        body: String,
        attachments: List<OutgoingAttachment>,
        onStatus: (String) -> Unit = {}
    ): String? {
        val repo = SmsRepository(context)
        val cleaned = recipients.map { it.trim() }.filter { it.isNotEmpty() }
        if (cleaned.isEmpty()) return "No recipient"

        val route = routeFor(cleaned, attachments.isNotEmpty()) { repo.isEmailAddress(it) }
        Log.i(TAG, "sending via $route to ${cleaned.size} recipient(s), attachments=${attachments.map { it.mime }}")

        if (route == Route.SMS) {
            repo.sendMessage(cleaned[0], body)
            return null
        }

        val parts = try {
            // Each attachment gets an equal share of the carrier's limit.
            val budget = AttachmentPolicy.budgetFor(body) / attachments.size.coerceAtLeast(1)
            val count = if (attachments.size > 1) " (%d of ${attachments.size})" else ""
            attachments.mapIndexed { i, attachment ->
                val which = count.format(i + 1)
                if (attachment.isVideo) onStatus("Compressing video…$which")
                AttachmentPolicy.prepare(context, attachment, budget) { pct ->
                    onStatus("Compressing video… $pct%$which")
                }
            }.also(::makeNamesUnique)
        } catch (e: AttachmentPolicy.TooLargeException) {
            return e.message ?: "Attachment too large"
        } catch (e: Exception) {
            Log.e(TAG, "couldn't prepare attachment", e)
            return "Couldn't prepare the attachment: ${e.message}"
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

    /** Each part's Content-ID comes from its name (MmsTransmitter), so two
     *  photos that happen to share a file name would collide. Numbers the
     *  repeats: "photo.jpg", "2_photo.jpg". */
    private fun makeNamesUnique(parts: List<com.google.android.mms.MMSPart>) {
        val seen = HashSet<String>()
        parts.forEachIndexed { i, part ->
            val name = part.name ?: "attachment"
            part.name = if (seen.add(name)) name else "${i + 1}_$name"
            seen.add(part.name)
        }
    }
}
