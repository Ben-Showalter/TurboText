package com.turbotext.app

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.provider.ContactsContract
import android.provider.Telephony
import android.telephony.SmsManager

/** Most carriers cap an MMS around ~1MB end-to-end (often less, and the
 *  stricter of sender/recipient carrier limits wins), but the sending
 *  library (com.klinkerapps:android-smsmms) always JPEG-compresses
 *  attached images at a fixed quality with no size awareness of its own.
 *  A modern full-resolution camera photo (often 3-8MB) sails past that
 *  limit — the carrier accepts the upload and reports "sent" back to us,
 *  then silently fails to relay it to the recipient. This target leaves
 *  headroom under the ~1MB ceiling for PDU/SMIL overhead. */
private const val MMS_MAX_IMAGE_BYTES = 900_000

/**
 * Thin wrapper around Android's SMS ContentProvider.
 * Only works correctly once this app is set as the default SMS app
 * (Android restricts writes to Telephony.Sms otherwise).
 */
class SmsRepository(private val context: Context) {

    /** Cheap existence check (not a full conversation load) — used by
     *  KeyButtonAccessibilityService to decide whether a hardware key
     *  press should trigger a brief outer-screen pulse.
     *
     *  Trashed messages are excluded: "Move to Trash" only hides a
     *  message locally (see TrashHelper) without touching the real
     *  provider's READ flag, so an unread message/thread that gets
     *  trashed without ever being opened would otherwise stay READ=0
     *  forever — keeping this true, and the outer-screen pulse
     *  blinking on every key press system-wide, indefinitely with no
     *  way for the user to clear it. */
    fun hasAnyUnread(): Boolean {
        val trashedKeys = TrashHelper.allTrashedKeys(context)
        val smsUnread = context.contentResolver.query(
            Telephony.Sms.CONTENT_URI, arrayOf(Telephony.Sms._ID),
            "${Telephony.Sms.READ}=0", null, null
        )?.use { c ->
            var found = false
            while (c.moveToNext()) {
                if ("sms:${c.getLong(0)}" !in trashedKeys) { found = true; break }
            }
            found
        } ?: false
        if (smsUnread) return true
        return context.contentResolver.query(
            Telephony.Mms.CONTENT_URI, arrayOf(Telephony.Mms._ID),
            "${Telephony.Mms.READ}=0", null, null
        )?.use { c ->
            var found = false
            while (c.moveToNext()) {
                if ("mms:${c.getLong(0)}" !in trashedKeys) { found = true; break }
            }
            found
        } ?: false
    }

    /** Best-effort detection of existing group (multi-recipient) MMS
     *  threads, via the threads table's recipient_ids column (a
     *  space-separated list of canonical-address IDs — more than one
     *  means a group thread). This hasn't been verified against this
     *  device's actual provider; if it comes back empty even when group
     *  threads genuinely exist, the column/URI names may differ here,
     *  the same class of OEM quirk this project has hit before. */
    /** The provider's own authoritative participant list for a specific
     *  thread (via recipient_ids, same mechanism as getGroupThreads),
     *  rather than trusting our own PDU-byte-scanning heuristic
     *  (extractAllAddresses) — the two can disagree, and this is the
     *  one that's actually correct, since it comes from what the
     *  provider itself resolved after insertReceivedMms ran. Returns a
     *  single-element list for a normal 1:1 thread. */
    fun getThreadParticipants(threadId: Long): List<String> {
        return try {
            context.contentResolver.query(
                android.net.Uri.parse("content://mms-sms/conversations?simple=true"),
                arrayOf("recipient_ids"),
                "_id = ?", arrayOf(threadId.toString()), null
            )?.use {
                if (it.moveToFirst()) {
                    val ids = (it.getString(0) ?: "").trim().split(" ").filter { s -> s.isNotEmpty() }
                    ids.mapNotNull { id -> lookupCanonicalAddress(id) }
                } else emptyList()
            } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun getGroupThreads(): List<Conversation> {
        val result = mutableListOf<Conversation>()
        try {
            val cursor = context.contentResolver.query(
                android.net.Uri.parse("content://mms-sms/conversations?simple=true"),
                arrayOf("_id", "recipient_ids", "snippet", "date", "read"),
                null, null, "date DESC"
            ) ?: return result
            cursor.use {
                while (it.moveToNext()) {
                    val recipientIds = it.getString(it.getColumnIndexOrThrow("recipient_ids")) ?: continue
                    val ids = recipientIds.trim().split(" ").filter { id -> id.isNotEmpty() }
                    if (ids.size < 2) continue // single-recipient — not a group thread
                    val threadId = it.getLong(it.getColumnIndexOrThrow("_id"))
                    val snippet = it.getString(it.getColumnIndexOrThrow("snippet")) ?: ""
                    val date = it.getLong(it.getColumnIndexOrThrow("date"))
                    val read = it.getInt(it.getColumnIndexOrThrow("read")) == 1
                    val addresses = ids.mapNotNull { id -> lookupCanonicalAddress(id) }
                    val names = addresses.map { addr -> lookupContactName(addr) ?: addr }
                    val displayName = GroupNicknameHelper.getNickname(context, threadId) ?: names.joinToString(", ")
                    result.add(
                        Conversation(
                            threadId = threadId,
                            address = addresses.joinToString(","),
                            displayName = displayName,
                            snippet = snippet,
                            date = date,
                            unread = !read
                        )
                    )
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("TurboTextGroup", "getGroupThreads query failed — unverified provider assumption may not hold on this device", e)
        }
        // Matches getConversations()'s behavior: a thread whose messages
        // are all trashed correctly disappears rather than lingering
        // with a stale snippet.
        return result.filter { getMessages(it.threadId).isNotEmpty() }
    }

    private fun lookupCanonicalAddress(id: String): String? {
        return try {
            context.contentResolver.query(
                android.net.Uri.parse("content://mms-sms/canonical-address/$id"),
                null, null, null, null
            )?.use { if (it.moveToFirst()) it.getString(0) else null }
        } catch (e: Exception) {
            null
        }
    }

    /** Attempts a true multi-recipient group MMS send — one shared
     *  envelope, so (carrier and recipient devices permitting) everyone
     *  sees the same thread and each other's replies. This is the
     *  single most uncertain piece of this whole feature: it depends on
     *  the underlying library actually supporting multiple "to"
     *  addresses the way this is written, and on carrier-level group MMS
     *  support that can't be verified or guaranteed from here. */
    fun sendGroupMmsMessage(addresses: List<String>, body: String) {
        val settings = com.klinker.android.send_message.Settings()
        settings.setUseSystemSending(true)
        // Confirmed via the library's own source and documentation: this
        // is what actually controls whether a multi-recipient message
        // goes out as one shared group MMS envelope versus separate
        // individual messages to each address. Without this, "group"
        // sends were silently falling back to the latter — exactly the
        // "doesn't load as a group on the recipient's end" symptom.
        settings.setGroup(true)
        // Normalizing every address to the same plain-digit format
        // (dropping formatting characters, keeping a leading + if
        // present) — a real test showed one address as "(555) 491-8332"
        // and the other as "+1 555-535-6558", genuinely inconsistent
        // formatting between recipients in the same send, which is a
        // plausible cause for only one being correctly processed.
        val normalized = addresses.map { normalizePhoneNumber(it) }
        val transaction = com.klinker.android.send_message.Transaction(context, settings)
        val message = com.klinker.android.send_message.Message(body, normalized.toTypedArray())
        android.util.Log.i("TurboTextGroup", "sending group MMS to (normalized): ${normalized.joinToString(", ")}")
        transaction.sendNewMessage(message, com.klinker.android.send_message.Transaction.NO_THREAD_ID)
    }

    /** Deliberately simple — good enough to distinguish "someone@email.com"
     *  from a phone number, not meant to be a full RFC-5322 validator. */
    fun isEmailAddress(address: String): Boolean =
        address.contains("@") && address.matches(Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$"))

    private fun normalizePhoneNumber(raw: String): String {
        // An email address isn't a phone number at all — stripping it down
        // to digits-only would destroy it. MMS (unlike SMS) can address a
        // recipient by email, routed through the carrier's MMSC, so this
        // needs to reach the send call untouched.
        if (isEmailAddress(raw.trim())) return raw.trim()
        val digitsOnly = raw.filter { it.isDigit() }
        // Both a bare 10-digit US number and an 11-digit one already
        // starting with 1 should end up in the exact same +1XXXXXXXXXX
        // shape — a real test showed one recipient normalized to plain
        // digits and the other kept its +1, still inconsistent between
        // the two, which was the likely point of this whole fix.
        return when (digitsOnly.length) {
            10 -> "+1$digitsOnly"
            11 -> if (digitsOnly.startsWith("1")) "+$digitsOnly" else "+1$digitsOnly"
            else -> if (raw.trim().startsWith("+")) "+$digitsOnly" else digitsOnly
        }
    }

    fun getConversations(limit: Int? = null): List<Conversation> {
        // threadId -> best (most recent, non-trashed) candidate seen so
        // far, across BOTH tables — previously this only ever looked at
        // Sms, so a thread whose latest activity was a picture message
        // (MMS-only, no accompanying text) never moved up the list or
        // updated its snippet at all.
        val candidates = mutableMapOf<Long, Conversation>()
        val trashedKeys = TrashHelper.allTrashedKeys(context)

        val smsSortOrder = if (limit != null) {
            "${Telephony.Sms.DATE} DESC LIMIT 500"
        } else {
            "${Telephony.Sms.DATE} DESC"
        }
        val smsCursor = context.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(
                Telephony.Sms._ID, Telephony.Sms.THREAD_ID, Telephony.Sms.ADDRESS,
                Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.READ
            ),
            null, null, smsSortOrder
        )
        smsCursor?.use {
            while (it.moveToNext()) {
                val id = it.getLong(it.getColumnIndexOrThrow(Telephony.Sms._ID))
                if (trashedKeys.contains("sms:$id")) continue
                val threadId = it.getLong(it.getColumnIndexOrThrow(Telephony.Sms.THREAD_ID))
                val date = it.getLong(it.getColumnIndexOrThrow(Telephony.Sms.DATE))
                if ((candidates[threadId]?.date ?: -1L) >= date) continue
                val address = it.getString(it.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)) ?: "Unknown"
                val body = it.getString(it.getColumnIndexOrThrow(Telephony.Sms.BODY)) ?: ""
                val read = it.getInt(it.getColumnIndexOrThrow(Telephony.Sms.READ)) == 1
                val draft = DraftHelper.getDraft(context, address)
                val snippet = when {
                    draft != null -> "Draft: $draft"
                    VcardTextExtractor.extract(body) != null -> "Contact card"
                    else -> body
                }
                candidates[threadId] = Conversation(
                    threadId = threadId,
                    address = address,
                    displayName = lookupContactName(address) ?: address,
                    snippet = snippet,
                    date = date,
                    unread = !read
                )
            }
        }

        val mmsSortOrder = if (limit != null) {
            "${Telephony.Mms.DATE} DESC LIMIT 500"
        } else {
            "${Telephony.Mms.DATE} DESC"
        }
        val mmsCursor = context.contentResolver.query(
            Telephony.Mms.CONTENT_URI,
            arrayOf(Telephony.Mms._ID, Telephony.Mms.THREAD_ID, Telephony.Mms.DATE, Telephony.Mms.READ),
            null, null, mmsSortOrder
        )
        mmsCursor?.use {
            while (it.moveToNext()) {
                val id = it.getLong(it.getColumnIndexOrThrow(Telephony.Mms._ID))
                if (trashedKeys.contains("mms:$id")) continue
                val threadId = it.getLong(it.getColumnIndexOrThrow(Telephony.Mms.THREAD_ID))
                val dateSeconds = it.getLong(it.getColumnIndexOrThrow(Telephony.Mms.DATE))
                val date = dateSeconds * 1000L
                if ((candidates[threadId]?.date ?: -1L) >= date) continue
                val read = it.getInt(it.getColumnIndexOrThrow(Telephony.Mms.READ)) == 1
                val parts = readMmsParts(id)
                val address = candidates[threadId]?.address ?: "Unknown"
                val snippet = when {
                    parts.text.isNotEmpty() -> parts.text
                    parts.imageUri != null -> "Picture message"
                    parts.vcardUri != null -> "Contact card"
                    else -> ""
                }
                candidates[threadId] = Conversation(
                    threadId = threadId,
                    address = address,
                    displayName = candidates[threadId]?.displayName ?: address,
                    snippet = snippet,
                    date = date,
                    unread = !read
                )
            }
        }

        // One query building a set of every group thread ID, instead of
        // a separate provider query per candidate (isGroupThread) — the
        // latter meant N extra queries for N conversations, which was a
        // real, meaningful slowdown on a device where each query has
        // real overhead.
        val groupThreadIds = try {
            val ids = mutableSetOf<Long>()
            context.contentResolver.query(
                android.net.Uri.parse("content://mms-sms/conversations?simple=true"),
                arrayOf("_id", "recipient_ids"),
                null, null, null
            )?.use {
                while (it.moveToNext()) {
                    val tid = it.getLong(0)
                    val recipientIds = (it.getString(1) ?: "").trim().split(" ").filter { s -> s.isNotEmpty() }
                    if (recipientIds.size > 1) ids.add(tid)
                }
            }
            ids
        } catch (e: Exception) {
            emptySet()
        }

        val sorted = candidates.values
            .filterNot { groupThreadIds.contains(it.threadId) }
            .sortedByDescending { it.date }
        return if (limit != null) sorted.take(limit + 1) else sorted
    }

    fun getMessages(threadId: Long): List<Message> {
        val messages = getSmsMessages(threadId) + getMmsMessages(threadId)
        return messages.filter { !TrashHelper.isTrashed(context, it) }.sortedBy { it.date }
    }

    /** Fetches one page of older messages, [offset] messages back from the
     *  most recent (i.e. skipping the [offset] newest, then taking the
     *  next [limit]) — used for incremental "Load More" rather than
     *  pulling in the entire history at once. A true SQL-level OFFSET
     *  across the merged SMS+MMS result isn't directly expressible since
     *  they're separate tables, so this fetches enough rows from each to
     *  cover the page after merging, then slices precisely. */
    fun getMessagesPage(threadId: Long, offset: Int, limit: Int): List<Message> {
        val neededFromEachTable = offset + limit
        val sms = getSmsMessages(threadId, neededFromEachTable)
        val mms = getMmsMessages(threadId, neededFromEachTable)
        val merged = (sms + mms)
            .filter { !TrashHelper.isTrashed(context, it) }
            .sortedByDescending { it.date }
        return merged.drop(offset).take(limit).sortedBy { it.date }
    }

    /** Fast path for opening a thread: fetches only the most recent
     *  [limit] messages (via SQL LIMIT, so it's quick even on a long
     *  history) for immediate display, while the full history loads
     *  separately in the background. */
    fun getRecentMessages(threadId: Long, limit: Int): List<Message> {
        val messages = getRecentSmsMessages(threadId, limit) + getRecentMmsMessages(threadId, limit)
        return messages.filter { !TrashHelper.isTrashed(context, it) }.sortedBy { it.date }.takeLast(limit)
    }

    private fun getRecentSmsMessages(threadId: Long, limit: Int): List<Message> =
        getSmsMessages(threadId, limit)

    private fun getRecentMmsMessages(threadId: Long, limit: Int): List<Message> =
        getMmsMessages(threadId, limit)

    private fun getSmsMessages(threadId: Long, limit: Int? = null): List<Message> {
        val result = mutableListOf<Message>()
        val uri = Telephony.Sms.CONTENT_URI
        val projection = arrayOf(
            Telephony.Sms._ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
            Telephony.Sms.TYPE,
            Telephony.Sms.STATUS
        )
        val sortOrder = if (limit != null) "${Telephony.Sms.DATE} DESC LIMIT $limit" else "${Telephony.Sms.DATE} ASC"
        val cursor = context.contentResolver.query(
            uri, projection,
            "${Telephony.Sms.THREAD_ID} = ?",
            arrayOf(threadId.toString()),
            sortOrder
        ) ?: return result

        cursor.use {
            while (it.moveToNext()) {
                val id = it.getLong(it.getColumnIndexOrThrow(Telephony.Sms._ID))
                val address = it.getString(it.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)) ?: ""
                val body = it.getString(it.getColumnIndexOrThrow(Telephony.Sms.BODY)) ?: ""
                val date = it.getLong(it.getColumnIndexOrThrow(Telephony.Sms.DATE))
                val type = it.getInt(it.getColumnIndexOrThrow(Telephony.Sms.TYPE))
                val status = it.getInt(it.getColumnIndexOrThrow(Telephony.Sms.STATUS))
                // 1 = inbox (incoming), 2 = sent, 4 = outbox (still
                // sending), 5 = failed — the latter two are also
                // outgoing, just not yet confirmed either way.
                val isOutgoing = type == 2 || type == 4 || type == 5
                val sendStatus = when {
                    type == 2 && status == Telephony.Sms.STATUS_COMPLETE -> "delivered"
                    type == 2 -> "sent"
                    type == 4 -> "sending"
                    type == 5 -> "failed"
                    type == 1 -> "received"
                    else -> null
                }
                val vcardBlock = VcardTextExtractor.extract(body)
                if (vcardBlock != null) {
                    val displayBody = (body.substring(0, body.indexOf(vcardBlock)) +
                        body.substring(body.indexOf(vcardBlock) + vcardBlock.length)).trim()
                    result.add(
                        Message(
                            id, address, displayBody, date, isOutgoing = isOutgoing,
                            vcardUri = materializeSmsVcard(id, vcardBlock), sendStatus = sendStatus
                        )
                    )
                } else {
                    result.add(Message(id, address, body, date, isOutgoing = isOutgoing, sendStatus = sendStatus))
                }
            }
        }
        return result
    }

    /** Reads MMS rows for a thread. Text/plain and image parts are pulled from
     *  the mms "part" table; sender/recipient addresses are skipped since the
     *  thread-level address (passed in from the conversation list) already
     *  covers 1:1 conversations, which is all this app targets. */
    private fun isGroupThread(threadId: Long): Boolean {
        return try {
            context.contentResolver.query(
                android.net.Uri.parse("content://mms-sms/conversations?simple=true"),
                arrayOf("recipient_ids"),
                "_id = ?", arrayOf(threadId.toString()), null
            )?.use {
                if (it.moveToFirst()) {
                    val ids = (it.getString(0) ?: "").trim().split(" ").filter { s -> s.isNotEmpty() }
                    ids.size > 1
                } else false
            } ?: false
        } catch (e: Exception) {
            false
        }
    }

    private fun lookupMmsSenderName(messageId: Long): String? {
        return try {
            context.contentResolver.query(
                android.net.Uri.parse("content://mms/$messageId/addr"),
                arrayOf("address", "type"),
                "type = ?", arrayOf("137"), null // 137 = FROM, per PduHeaders' address-type constants
            )?.use {
                if (it.moveToFirst()) {
                    val addr = it.getString(0)
                    if (addr != null) ContactHelper.lookupName(context, addr) ?: addr else null
                } else null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun getMmsMessages(threadId: Long, limit: Int? = null): List<Message> {
        val result = mutableListOf<Message>()
        val isGroup = isGroupThread(threadId)
        val uri = Telephony.Mms.CONTENT_URI
        val projection = arrayOf(Telephony.Mms._ID, Telephony.Mms.DATE, Telephony.Mms.MESSAGE_BOX)
        val sortOrder = if (limit != null) "${Telephony.Mms.DATE} DESC LIMIT $limit" else "${Telephony.Mms.DATE} ASC"
        val cursor = context.contentResolver.query(
            uri, projection,
            "${Telephony.Mms.THREAD_ID} = ?",
            arrayOf(threadId.toString()),
            sortOrder
        ) ?: return result

        cursor.use {
            while (it.moveToNext()) {
                val id = it.getLong(it.getColumnIndexOrThrow(Telephony.Mms._ID))
                // MMS dates are stored in seconds, unlike SMS (milliseconds).
                val dateSeconds = it.getLong(it.getColumnIndexOrThrow(Telephony.Mms.DATE))
                val box = it.getInt(it.getColumnIndexOrThrow(Telephony.Mms.MESSAGE_BOX))
                val isOutgoing = box == Telephony.Mms.MESSAGE_BOX_SENT || box == Telephony.Mms.MESSAGE_BOX_OUTBOX
                val sendStatus = when (box) {
                    Telephony.Mms.MESSAGE_BOX_SENT -> "sent"
                    Telephony.Mms.MESSAGE_BOX_OUTBOX -> "sending"
                    Telephony.Mms.MESSAGE_BOX_FAILED -> "failed"
                    Telephony.Mms.MESSAGE_BOX_INBOX -> "received"
                    else -> null
                }
                val parts = readMmsParts(id)
                val senderName = if (isGroup && !isOutgoing) lookupMmsSenderName(id) else null
                result.add(
                    Message(
                        id = id,
                        address = "",
                        body = parts.text,
                        date = dateSeconds * 1000L,
                        isOutgoing = isOutgoing,
                        isMms = true,
                        imageUri = parts.imageUri,
                        vcardUri = parts.vcardUri,
                        senderName = senderName,
                        isUnretrievedMms = parts.text.isEmpty() && parts.imageUri == null && parts.vcardUri == null,
                        sendStatus = sendStatus
                    )
                )
            }
        }
        return result
    }

    /** Writes a vCard found inline in a plain SMS body out to a real file
     *  so it can be handed to the same content-URI-based "Import Contact"
     *  flow (ConversationActivity.importVcard) that MMS vCard parts
     *  already use — named by SMS row id so re-reading the same message
     *  doesn't rewrite the file every time. */
    private fun materializeSmsVcard(smsId: Long, vcardText: String): String? {
        return try {
            val dir = java.io.File(context.cacheDir, "sms_vcards").apply { mkdirs() }
            val file = java.io.File(dir, "sms_$smsId.vcf")
            if (!file.exists()) file.writeText(vcardText)
            androidx.core.content.FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", file
            ).toString()
        } catch (e: Exception) {
            android.util.Log.w("TurboTextVcard", "failed to materialize SMS vcard for id=$smsId", e)
            null
        }
    }

    private data class MmsParts(
        val text: String,
        val imageUri: String?,
        val vcardUri: String? = null,
        val senderAddress: String? = null
    )

    private fun readMmsParts(messageId: Long): MmsParts {
        var text = ""
        var imageUri: String? = null
        var vcardUri: String? = null
        val partUri = android.net.Uri.parse("content://mms/part")
        val cursor = context.contentResolver.query(
            partUri, arrayOf("_id", "ct", "text"),
            "mid = ?", arrayOf(messageId.toString()), null
        )

        cursor?.use {
            while (it.moveToNext()) {
                val partId = it.getLong(0)
                val contentType = it.getString(1) ?: ""
                when {
                    contentType == "text/plain" -> it.getString(2)?.let { t -> text = t }
                    contentType.startsWith("image/") -> imageUri = "content://mms/part/$partId"
                    contentType == "text/x-vcard" || contentType == "text/vcard" ->
                        vcardUri = "content://mms/part/$partId"
                }
            }
        }

        // Who actually sent this specific message — mainly useful in a
        // group thread, where an incoming message could be from any of
        // several people, not just "the other side of a 1:1 chat".
        var senderAddress: String? = null
        try {
            val addrCursor = context.contentResolver.query(
                android.net.Uri.parse("content://mms/$messageId/addr"),
                arrayOf("address", "type"),
                "type = 137", null, null // 137 = FROM, per PduHeaders' address-type constants
            )
            addrCursor?.use { if (it.moveToFirst()) senderAddress = it.getString(0) }
        } catch (e: Exception) {
            // Non-critical — the message still displays fine without a sender label.
        }

        return MmsParts(text, imageUri, vcardUri, senderAddress)
    }

    /** Sends an MMS (with an optional photo) using the system MmsManager
     *  (SDK 21+) via the android-smsmms library, which composes the PDU. */
    /** Inserts a downloaded incoming MMS (text and/or image) into the
     *  standard Mms/Part provider tables, so it shows up through the same
     *  getMessages() query as everything else. */
    /** Fetches every trashed message (across all conversations) for the
     *  Trash Bin screen — view-only for now, matching what's actually
     *  been asked for; nothing here restores or permanently deletes. */
    /** Called at app startup — actually deletes any trash entry older
     *  than 14 days from the real Sms/Mms provider (not just untracking
     *  it, which would just make it reappear in its thread). */
    fun purgeExpiredTrash() {
        TrashHelper.purgeExpired(context) { isMms, id ->
            if (isMms) {
                context.contentResolver.delete(
                    android.net.Uri.withAppendedPath(Telephony.Mms.CONTENT_URI, id.toString()), null, null
                )
            } else {
                context.contentResolver.delete(
                    android.net.Uri.withAppendedPath(Telephony.Sms.CONTENT_URI, id.toString()), null, null
                )
            }
        }
    }

    fun getTrashedMessages(): List<Message> {
        val keys = TrashHelper.allTrashedKeys(context)
        val smsIds = keys.filter { it.startsWith("sms:") }.mapNotNull { it.removePrefix("sms:").toLongOrNull() }
        val mmsIds = keys.filter { it.startsWith("mms:") }.mapNotNull { it.removePrefix("mms:").toLongOrNull() }

        val result = mutableListOf<Message>()

        if (smsIds.isNotEmpty()) {
            val placeholders = smsIds.joinToString(",") { "?" }
            val projection = arrayOf(
                Telephony.Sms._ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY,
                Telephony.Sms.DATE, Telephony.Sms.TYPE
            )
            context.contentResolver.query(
                Telephony.Sms.CONTENT_URI, projection,
                "${Telephony.Sms._ID} IN ($placeholders)",
                smsIds.map { it.toString() }.toTypedArray(), null
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(cursor.getColumnIndexOrThrow(Telephony.Sms._ID))
                    val address = cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)) ?: ""
                    val body = cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)) ?: ""
                    val date = cursor.getLong(cursor.getColumnIndexOrThrow(Telephony.Sms.DATE))
                    val type = cursor.getInt(cursor.getColumnIndexOrThrow(Telephony.Sms.TYPE))
                    result.add(Message(id, address, body, date, isOutgoing = type == 2))
                }
            }
        }

        if (mmsIds.isNotEmpty()) {
            val placeholders = mmsIds.joinToString(",") { "?" }
            val projection = arrayOf(Telephony.Mms._ID, Telephony.Mms.DATE, Telephony.Mms.MESSAGE_BOX)
            context.contentResolver.query(
                Telephony.Mms.CONTENT_URI, projection,
                "${Telephony.Mms._ID} IN ($placeholders)",
                mmsIds.map { it.toString() }.toTypedArray(), null
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(cursor.getColumnIndexOrThrow(Telephony.Mms._ID))
                    val dateSeconds = cursor.getLong(cursor.getColumnIndexOrThrow(Telephony.Mms.DATE))
                    val box = cursor.getInt(cursor.getColumnIndexOrThrow(Telephony.Mms.MESSAGE_BOX))
                    val isOutgoing = box == Telephony.Mms.MESSAGE_BOX_SENT || box == Telephony.Mms.MESSAGE_BOX_OUTBOX
                    val parts = readMmsParts(id)
                    result.add(
                        Message(
                            id = id, address = "", body = parts.text, date = dateSeconds * 1000L,
                            isOutgoing = isOutgoing, isMms = true, imageUri = parts.imageUri,
                            vcardUri = parts.vcardUri,
                            isUnretrievedMms = parts.text.isEmpty() && parts.imageUri == null && parts.vcardUri == null
                        )
                    )
                }
            }
        }

        return result.sortedByDescending { it.date }
    }

    /** Finds/creates the shared thread for a group MMS's full
     *  participant set, excluding our own number where we can determine
     *  it (Android's threading expects the OTHER participants, not the
     *  local device). Returns null (caller falls back to a sender-only
     *  thread) if this can't be resolved for any reason, rather than
     *  ever risk losing the message. */
    private fun resolveGroupThreadId(participants: List<String>, tag: String): Long? {
        return try {
            val ownNumber = getOwnPhoneNumber()?.let { normalizePhoneNumber(it) }
            val others = participants.map { normalizePhoneNumber(it) }.distinct()
                .filter { ownNumber == null || it != ownNumber }
            if (others.size < 2) {
                android.util.Log.i(tag, "resolveGroupThreadId: fewer than 2 distinct others after filtering self — not a group after all")
                return null
            }
            android.util.Log.i(tag, "resolveGroupThreadId: own=$ownNumber, group=${others.joinToString(", ")}")
            android.provider.Telephony.Threads.getOrCreateThreadId(context, others.toSet())
        } catch (e: Exception) {
            android.util.Log.w(tag, "resolveGroupThreadId failed, falling back to sender-only thread", e)
            null
        }
    }

    /** Best-effort — a known-flaky API on some carriers/devices. If it
     *  comes back empty, our own number just won't get filtered out of
     *  the group's participant set, which could create a differently
     *  keyed thread than one the stock Messages app made from the same
     *  group previously. */
    private fun getOwnPhoneNumber(): String? {
        return try {
            @Suppress("DEPRECATION", "MissingPermission")
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? android.telephony.TelephonyManager
            @Suppress("DEPRECATION", "MissingPermission")
            tm?.line1Number?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            null
        }
    }

    fun insertReceivedMms(
        address: String,
        text: String,
        imageBytes: ByteArray?,
        vcardBytes: ByteArray? = null,
        groupParticipants: List<String> = emptyList()
    ): Long? {
        val tag = "TurboTextMms"
        return try {
            val threadId = if (groupParticipants.size > 1) {
                resolveGroupThreadId(groupParticipants, tag) ?: android.provider.Telephony.Threads.getOrCreateThreadId(context, address)
            } else {
                android.provider.Telephony.Threads.getOrCreateThreadId(context, address)
            }
            val values = android.content.ContentValues().apply {
                put(Telephony.Mms.THREAD_ID, threadId)
                put(Telephony.Mms.DATE, System.currentTimeMillis() / 1000L) // Mms dates are in seconds
                put(Telephony.Mms.MESSAGE_BOX, Telephony.Mms.MESSAGE_BOX_INBOX)
                put(Telephony.Mms.READ, 0)
            }
            val mmsUri = context.contentResolver.insert(Telephony.Mms.CONTENT_URI, values)
            android.util.Log.i(tag, "insertReceivedMms: mms row insert -> $mmsUri")
            if (mmsUri == null) return null
            val messageId = android.content.ContentUris.parseId(mmsUri)

            // Sender address row — not required for our own reading path
            // (which doesn't look at it for MMS), but keeps the message
            // consistent for other apps that might read this provider.
            try {
                val addrValues = android.content.ContentValues().apply {
                    put("address", address)
                    put("charset", 106) // 106 = UTF-8, per the MIBenum charset registry
                    put("type", 137) // 137 = FROM, per PduHeaders' address-type constants
                }
                val addrUri = context.contentResolver.insert(
                    android.net.Uri.parse("content://mms/$messageId/addr"), addrValues
                )
                android.util.Log.i(tag, "insertReceivedMms: addr insert -> $addrUri")
            } catch (e: Exception) {
                android.util.Log.w(tag, "insertReceivedMms: addr insert failed (non-critical)", e)
            }

            if (text.isNotEmpty()) {
                try {
                    val textPartValues = android.content.ContentValues().apply {
                        put("mid", messageId)
                        put("ct", "text/plain")
                        put("text", text)
                    }
                    val textUri = context.contentResolver.insert(android.net.Uri.parse("content://mms/$messageId/part"), textPartValues)
                    android.util.Log.i(tag, "insertReceivedMms: text part insert -> $textUri")
                } catch (e: Exception) {
                    android.util.Log.w(tag, "insertReceivedMms: text part insert failed", e)
                }
            }

            if (imageBytes != null) {
                try {
                    val imagePartValues = android.content.ContentValues().apply {
                        put("mid", messageId)
                        put("ct", "image/jpeg")
                        put("name", "image.jpg")
                    }
                    val partUri = context.contentResolver.insert(android.net.Uri.parse("content://mms/$messageId/part"), imagePartValues)
                    android.util.Log.i(tag, "insertReceivedMms: image part insert -> $partUri, ${imageBytes.size} bytes to write")
                    if (partUri != null) {
                        val stream = context.contentResolver.openOutputStream(partUri)
                        android.util.Log.i(tag, "insertReceivedMms: openOutputStream -> ${if (stream != null) "got stream" else "NULL"}")
                        stream?.use { it.write(imageBytes) }
                        android.util.Log.i(tag, "insertReceivedMms: image bytes written")
                    }
                } catch (e: Exception) {
                    android.util.Log.e(tag, "insertReceivedMms: image part insert/write failed", e)
                }
            }

            if (vcardBytes != null) {
                try {
                    val vcardPartValues = android.content.ContentValues().apply {
                        put("mid", messageId)
                        put("ct", "text/x-vcard")
                        put("name", "contact.vcf")
                    }
                    val partUri = context.contentResolver.insert(android.net.Uri.parse("content://mms/$messageId/part"), vcardPartValues)
                    android.util.Log.i(tag, "insertReceivedMms: vcard part insert -> $partUri, ${vcardBytes.size} bytes to write")
                    if (partUri != null) {
                        context.contentResolver.openOutputStream(partUri)?.use { it.write(vcardBytes) }
                        android.util.Log.i(tag, "insertReceivedMms: vcard bytes written")
                    }
                } catch (e: Exception) {
                    android.util.Log.e(tag, "insertReceivedMms: vcard part insert/write failed", e)
                }
            }

            threadId
        } catch (e: Exception) {
            android.util.Log.e(tag, "insertReceivedMms: unexpected failure", e)
            null
        }
    }

    /** [imageUri]/[audioUri]/[vcardBytes] are mutually exclusive in
     *  practice (the UI only ever sets one at a time), but nothing here
     *  enforces that — whichever are non-null get attached.
     *
     *  Audio/vcard attachment uses Message.Part — confirmed for real this
     *  time: the constructor (media, contentType, name) resolved cleanly
     *  in Android Studio, and attaching it goes through message.parts
     *  (a confirmed-real mutable list — Message.Part) rather than an
     *  addPart() method, which turned out not to exist. */
    fun sendMmsMessage(
        address: String,
        body: String,
        imageUri: android.net.Uri? = null,
        audioUri: android.net.Uri? = null,
        vcardBytes: ByteArray? = null,
        vcardName: String = "contact.vcf"
    ) {
        val settings = com.klinker.android.send_message.Settings()
        settings.setUseSystemSending(true)
        val transaction = com.klinker.android.send_message.Transaction(context, settings)
        val message = com.klinker.android.send_message.Message(body, address)
        var hasAttachment = false
        if (imageUri != null) {
            val bitmap = loadFullResolutionBitmap(imageUri)
            if (bitmap != null) { message.setImage(fitBitmapForMms(bitmap)); hasAttachment = true }
        }
        if (audioUri != null) {
            try {
                val bytes = context.contentResolver.openInputStream(audioUri)?.use { it.readBytes() }
                if (bytes != null) {
                    val part = com.klinker.android.send_message.Message.Part(
                        bytes, "audio/mp4", "audio_${System.currentTimeMillis()}.m4a"
                    )
                    message.parts.add(part)
                    hasAttachment = true
                }
            } catch (e: Exception) {
                android.util.Log.w("TurboTextAttach", "failed to attach audio", e)
            }
        }
        if (vcardBytes != null) {
            try {
                val part = com.klinker.android.send_message.Message.Part(vcardBytes, "text/x-vcard", vcardName)
                message.parts.add(part)
                hasAttachment = true
            } catch (e: Exception) {
                android.util.Log.w("TurboTextAttach", "failed to attach vcard", e)
            }
        }
        // A real log capture showed a plain-text send to an email address
        // going out via SmsManager.sendTextMessage() anyway — a phone
        // destination is all that transport can actually reach, so it
        // just hung on "Sending" forever. The library's SMS-vs-MMS choice
        // appears to key off whether the message has any attached parts
        // — this reuses that same already-proven mechanism (identical to
        // how photos/audio/vcards already reliably go out as MMS) rather
        // than relying on an unfamiliar library setting I can't verify
        // the exact behavior of.
        if (!hasAttachment && isEmailAddress(address.trim())) {
            try {
                val part = com.klinker.android.send_message.Message.Part(
                    body.toByteArray(), "text/plain", "text_0.txt"
                )
                message.parts.add(part)
            } catch (e: Exception) {
                android.util.Log.w("TurboTextAttach", "failed to force MMS transport for email address", e)
            }
        }
        transaction.sendNewMessage(message, com.klinker.android.send_message.Transaction.NO_THREAD_ID)
    }

    /** Decodes an image at its actual, full resolution — downscaling for
     *  MMS delivery (if needed at all) happens separately in
     *  [fitBitmapForMms], not here. The only safety net kept is catching
     *  OutOfMemoryError itself, so a truly huge image fails the attach
     *  cleanly instead of crashing the app — that's a crash-prevention
     *  floor, not a quality-reducing cap. */
    private fun loadFullResolutionBitmap(uri: android.net.Uri): android.graphics.Bitmap? {
        return try {
            context.contentResolver.openInputStream(uri)?.use {
                android.graphics.BitmapFactory.decodeStream(it)
            }
        } catch (e: OutOfMemoryError) {
            android.util.Log.w("TurboTextAttach", "image too large to load at full resolution", e)
            null
        } catch (e: Exception) {
            null
        }
    }

    /** Shrinks [original] only as much as needed to fit under
     *  [MMS_MAX_IMAGE_BYTES] once JPEG-compressed — an image that already
     *  fits is returned untouched. Measures against the library's actual
     *  compress step (JPEG @ quality 90, matching Message.bitmapToByteArray)
     *  rather than estimating, so the check reflects the real attachment
     *  size. Scale factor is computed from the byte-size ratio each pass
     *  (JPEG size scales roughly with pixel count) with a small safety
     *  margin, capped at a few iterations so a pathological image (e.g.
     *  one that resists compression) can't loop indefinitely. */
    private fun fitBitmapForMms(original: android.graphics.Bitmap): android.graphics.Bitmap {
        var current = original
        var size = jpegByteSize(current)
        var attempts = 0
        while (size > MMS_MAX_IMAGE_BYTES && attempts < 6) {
            val scale = kotlin.math.sqrt(MMS_MAX_IMAGE_BYTES.toDouble() / size.toDouble()) * 0.95
            val newWidth = (current.width * scale).toInt().coerceAtLeast(1)
            val newHeight = (current.height * scale).toInt().coerceAtLeast(1)
            val scaled = android.graphics.Bitmap.createScaledBitmap(current, newWidth, newHeight, true)
            if (scaled !== current) current.recycle()
            current = scaled
            size = jpegByteSize(current)
            attempts++
        }
        if (size > MMS_MAX_IMAGE_BYTES) {
            android.util.Log.w("TurboTextAttach", "image still ${size}B after $attempts downscale passes, sending anyway")
        }
        return current
    }

    private fun jpegByteSize(bitmap: android.graphics.Bitmap): Int {
        val stream = java.io.ByteArrayOutputStream()
        bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, stream)
        return stream.size()
    }

    /** Sends the message and writes a copy into the Sent folder ourselves (required for default SMS apps). */
    fun sendMessage(address: String, body: String) {
        // getSystemService(SmsManager::class.java) only exists on API 31+ and
        // returns null below that, which crashed Send on this phone's older
        // Android — getDefault() works on every version.
        val smsManager = SmsManager.getDefault()
        val parts = smsManager.divideMessage(body)

        val threadId = try {
            Telephony.Threads.getOrCreateThreadId(context, address)
        } catch (e: Exception) {
            null
        }
        val values = ContentValues().apply {
            put(Telephony.Sms.ADDRESS, address)
            put(Telephony.Sms.BODY, body)
            put(Telephony.Sms.DATE, System.currentTimeMillis())
            put(Telephony.Sms.READ, 1)
            // Starts as OUTBOX ("sending") — SmsSentReceiver flips this to
            // SENT or FAILED once SmsManager actually confirms the result,
            // rather than assuming success the moment it's handed off.
            put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_OUTBOX)
            if (threadId != null) put(Telephony.Sms.THREAD_ID, threadId)
        }
        val rowUri = context.contentResolver.insert(Telephony.Sms.CONTENT_URI, values)

        if (rowUri != null) {
            val sentIntent = Intent(context, SmsSentReceiver::class.java).apply {
                data = rowUri // makes this PendingIntent unique per message
                putExtra("row_uri", rowUri.toString())
                putExtra("thread_id", threadId ?: -1L)
            }
            val requestCode = rowUri.lastPathSegment?.toIntOrNull() ?: System.currentTimeMillis().toInt()
            val sentPendingIntent = android.app.PendingIntent.getBroadcast(
                context, requestCode, sentIntent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_MUTABLE
            )
            val sentIntents = ArrayList<android.app.PendingIntent>()
            for (i in parts.indices) sentIntents.add(sentPendingIntent)

            // Separate from sentIntent — this only fires if/when the
            // carrier reports the recipient's phone actually received
            // it, which not every carrier supports or enables.
            val deliveredIntent = Intent(context, SmsDeliveredReceiver::class.java).apply {
                data = rowUri
                putExtra("row_uri", rowUri.toString())
                putExtra("thread_id", threadId ?: -1L)
            }
            val deliveredPendingIntent = android.app.PendingIntent.getBroadcast(
                context, requestCode, deliveredIntent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_MUTABLE
            )
            val deliveredIntents = ArrayList<android.app.PendingIntent>()
            for (i in parts.indices) deliveredIntents.add(deliveredPendingIntent)

            smsManager.sendMultipartTextMessage(address, null, parts, sentIntents, deliveredIntents)
        } else {
            smsManager.sendMultipartTextMessage(address, null, parts, null, null)
        }

        if (threadId != null) {
            MessageCache.put(threadId, getMessages(threadId))
        }
    }

    fun markThreadRead(threadId: Long) {
        val smsValues = ContentValues().apply { put(Telephony.Sms.READ, 1) }
        context.contentResolver.update(
            Telephony.Sms.CONTENT_URI, smsValues,
            "${Telephony.Sms.THREAD_ID} = ?", arrayOf(threadId.toString())
        )
        val mmsValues = ContentValues().apply { put(Telephony.Mms.READ, 1) }
        context.contentResolver.update(
            Telephony.Mms.CONTENT_URI, mmsValues,
            "${Telephony.Mms.THREAD_ID} = ?", arrayOf(threadId.toString())
        )
    }

    private fun lookupContactName(phoneNumber: String): String? =
        ContactHelper.lookupName(context, phoneNumber)
}
