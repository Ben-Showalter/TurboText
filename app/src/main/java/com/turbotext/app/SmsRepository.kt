package com.turbotext.app

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.provider.ContactsContract
import android.provider.Telephony
import android.telephony.SmsManager

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
            Telephony.Sms.CONTENT_URI,
            arrayOf(Telephony.Sms._ID, Telephony.Sms.THREAD_ID, Telephony.Sms.ADDRESS),
            "${Telephony.Sms.READ}=0", null, null
        )?.use { c ->
            var found = false
            while (c.moveToNext()) {
                val id = c.getLong(0)
                if ("sms:$id" !in trashedKeys) {
                    // Diagnostic for a report that the outer-screen pulse
                    // kept blinking after the only visible conversation was
                    // opened and read — this logs exactly which row is
                    // still READ=0 and its thread/address, to tell whether
                    // it's genuinely a different, unopened thread (e.g. a
                    // self-sent SMS landing back in a thread_id that
                    // doesn't match the sent copy's) versus markThreadRead
                    // simply not having reached this row.
                    android.util.Log.i("TurboTextUnread", "unread sms: id=$id threadId=${c.getLong(1)} address=${c.getString(2)}")
                    found = true
                    break
                }
            }
            found
        } ?: false
        if (smsUnread) return true
        return context.contentResolver.query(
            Telephony.Mms.CONTENT_URI,
            arrayOf(Telephony.Mms._ID, Telephony.Mms.THREAD_ID),
            "${Telephony.Mms.READ}=0", null, null
        )?.use { c ->
            var found = false
            while (c.moveToNext()) {
                val id = c.getLong(0)
                if ("mms:$id" !in trashedKeys) {
                    android.util.Log.i("TurboTextUnread", "unread mms: id=$id threadId=${c.getLong(1)}")
                    found = true
                    break
                }
            }
            found
        } ?: false
    }

    /** Thread ids that currently have at least one unread (READ=0) SMS or
     *  MMS row, excluding rows hidden by the local trash. Cheap — ids
     *  only, no bodies or contact lookups. Shared by hasUnreadGroupMessage()
     *  and by GroupMessagesActivity's fast un-bold reconcile. */
    fun unreadThreadIds(): Set<Long> {
        val trashedKeys = TrashHelper.allTrashedKeys(context)
        val ids = mutableSetOf<Long>()
        try {
            context.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(Telephony.Sms._ID, Telephony.Sms.THREAD_ID),
                "${Telephony.Sms.READ}=0", null, null
            )?.use { c ->
                while (c.moveToNext()) {
                    if ("sms:${c.getLong(0)}" !in trashedKeys) ids.add(c.getLong(1))
                }
            }
            context.contentResolver.query(
                Telephony.Mms.CONTENT_URI,
                arrayOf(Telephony.Mms._ID, Telephony.Mms.THREAD_ID),
                "${Telephony.Mms.READ}=0", null, null
            )?.use { c ->
                while (c.moveToNext()) {
                    if ("mms:${c.getLong(0)}" !in trashedKeys) ids.add(c.getLong(1))
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("TurboTextGroup", "unreadThreadIds: scan failed", e)
        }
        return ids
    }

    /** True when at least one unread SMS/MMS row sits in a group
     *  (multi-recipient) thread — used to bold the "Groups" entry on the
     *  conversation list so a new group message isn't hidden behind that
     *  separate screen. Deliberately lighter than getGroupThreads(): it
     *  only reads ids, never loads message bodies or resolves contacts.
     *  Honours the local trash the same way hasAnyUnread() does. */
    fun hasUnreadGroupMessage(): Boolean {
        val unreadThreadIds = unreadThreadIds()
        android.util.Log.i("TurboTextGroup", "hasUnreadGroupMessage: unread threadIds=$unreadThreadIds")
        if (unreadThreadIds.isEmpty()) return false
        return try {
            var found = false
            // Read the WHOLE conversations view and match columns BY NAME —
            // this pseudo-provider ignores the projection and returns its
            // own fixed column set, so positional getString(1) would read
            // the wrong column (this is why getGroupThreads/getConversations
            // also use getColumnIndexOrThrow here).
            context.contentResolver.query(
                android.net.Uri.parse("content://mms-sms/conversations?simple=true"),
                null, null, null, null
            )?.use { c ->
                val idCol = c.getColumnIndex("_id")
                val recipCol = c.getColumnIndex("recipient_ids")
                if (idCol < 0 || recipCol < 0) {
                    android.util.Log.w("TurboTextGroup", "hasUnreadGroupMessage: columns not found (id=$idCol recip=$recipCol)")
                    return@use
                }
                while (c.moveToNext()) {
                    val tid = c.getLong(idCol)
                    if (tid !in unreadThreadIds) continue
                    val ids = (c.getString(recipCol) ?: "").trim().split(" ").filter { it.isNotEmpty() }
                    android.util.Log.i("TurboTextGroup", "hasUnreadGroupMessage: unread thread $tid has ${ids.size} recipients")
                    if (ids.size > 1) { found = true; break }
                }
            }
            // Fallback: the bulk conversations view didn't flag any of the
            // unread threads as a group — ask per-thread via the same
            // recipient_ids path MmsDownloadReceiver uses for its notification.
            if (!found) {
                for (tid in unreadThreadIds) {
                    val participants = getThreadParticipants(tid)
                    android.util.Log.i("TurboTextGroup", "hasUnreadGroupMessage: per-thread $tid -> ${participants.size} participants")
                    if (participants.size > 1) { found = true; break }
                }
            }
            android.util.Log.i("TurboTextGroup", "hasUnreadGroupMessage -> $found")
            found
        } catch (e: Exception) {
            android.util.Log.w("TurboTextGroup", "hasUnreadGroupMessage: thread lookup failed", e)
            false
        }
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
     *  — what the provider itself resolved when the MMS was saved.
     *  Returns a single-element list for a normal 1:1 thread. */
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
        // Per-thread unread state read straight from the individual
        // Sms/Mms rows (READ=0), not the conversations view's aggregate
        // "read" column — that column doesn't reliably flip when a fresh
        // inbound group MMS lands, so a new message never turned the row
        // bold. Same approach getConversations()/hasAnyUnread() already
        // use, and it honors the local trash the same way.
        val trashedKeys = TrashHelper.allTrashedKeys(context)
        val unreadThreadIds = mutableSetOf<Long>()
        try {
            context.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(Telephony.Sms._ID, Telephony.Sms.THREAD_ID),
                "${Telephony.Sms.READ}=0", null, null
            )?.use { c ->
                while (c.moveToNext()) {
                    if ("sms:${c.getLong(0)}" !in trashedKeys) unreadThreadIds.add(c.getLong(1))
                }
            }
            context.contentResolver.query(
                Telephony.Mms.CONTENT_URI,
                arrayOf(Telephony.Mms._ID, Telephony.Mms.THREAD_ID),
                "${Telephony.Mms.READ}=0", null, null
            )?.use { c ->
                while (c.moveToNext()) {
                    if ("mms:${c.getLong(0)}" !in trashedKeys) unreadThreadIds.add(c.getLong(1))
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("TurboTextGroup", "group unread scan failed", e)
        }
        try {
            val cursor = context.contentResolver.query(
                android.net.Uri.parse("content://mms-sms/conversations?simple=true"),
                arrayOf("_id", "recipient_ids", "snippet", "date"),
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
                            unread = unreadThreadIds.contains(threadId)
                        )
                    )
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("TurboTextGroup", "getGroupThreads query failed — unverified provider assumption may not hold on this device", e)
        }
        // Matches getConversations()'s behavior: a thread whose messages
        // are all trashed correctly disappears rather than lingering
        // with a stale snippet. The conversations view's own "date DESC"
        // isn't honored by every OEM provider, so re-derive each thread's
        // real timestamp from its newest surviving message and sort here.
        return result
            .mapNotNull { convo ->
                val msgs = getMessages(convo.threadId)
                if (msgs.isEmpty()) null else convo.copy(date = msgs.last().date)
            }
            .sortedByDescending { it.date }
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

    /** Deliberately simple — good enough to distinguish "someone@email.com"
     *  from a phone number, not meant to be a full RFC-5322 validator. */
    fun isEmailAddress(address: String): Boolean =
        address.contains("@") && address.matches(Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$"))

    fun normalizePhoneNumber(raw: String): String {
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
                    parts.audioUri != null -> "Voice message"
                    parts.vcardUri != null -> "Contact card"
                    parts.videoUri != null -> "Video"
                    parts.fileUri != null -> "File: ${parts.fileName ?: parts.fileMime}"
                    else -> "Multimedia message"
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
        val projection = arrayOf(Telephony.Mms._ID, Telephony.Mms.DATE, Telephony.Mms.MESSAGE_BOX, Telephony.Mms.MESSAGE_TYPE)
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
                // 130 = m-notification-ind: the carrier's "you have an MMS"
                // notice, persisted before (or instead of, if it failed)
                // the real content download.
                val isNotificationInd = it.getInt(it.getColumnIndexOrThrow(Telephony.Mms.MESSAGE_TYPE)) == 130
                val parts = readMmsParts(id)
                val senderName = if (isGroup && !isOutgoing) lookupMmsSenderName(id) else null
                result.add(
                    Message(
                        id = id,
                        address = if (!isOutgoing) parts.senderAddress ?: "" else "",
                        body = parts.text,
                        date = dateSeconds * 1000L,
                        isOutgoing = isOutgoing,
                        isMms = true,
                        imageUri = parts.imageUri,
                        vcardUri = parts.vcardUri,
                        audioUri = parts.audioUri,
                        videoUri = parts.videoUri,
                        fileUri = parts.fileUri,
                        fileMime = parts.fileMime,
                        fileName = parts.fileName,
                        senderName = senderName,
                        mmsDownloadPending = isNotificationInd,
                        isUnretrievedMms = !isNotificationInd && parts.text.isEmpty() && !parts.hasMedia,
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
        val audioUri: String? = null,
        val senderAddress: String? = null,
        val videoUri: String? = null,
        val fileUri: String? = null,
        val fileMime: String? = null,
        val fileName: String? = null
    ) {
        val hasMedia get() = imageUri != null || vcardUri != null || audioUri != null ||
            videoUri != null || fileUri != null
    }

    private fun readMmsParts(messageId: Long): MmsParts {
        val textParts = mutableListOf<String>()
        var imageUri: String? = null
        var vcardUri: String? = null
        var audioUri: String? = null
        var videoUri: String? = null
        var fileUri: String? = null
        var fileMime: String? = null
        var fileName: String? = null
        val partUri = android.net.Uri.parse("content://mms/part")
        val cursor = context.contentResolver.query(
            partUri, arrayOf("_id", "ct", "text", "_data", "name", "cl", "fn"),
            "mid = ?", arrayOf(messageId.toString()), null
        )

        cursor?.use {
            while (it.moveToNext()) {
                val partId = it.getLong(0)
                // "ct" can carry parameters ("text/plain; charset=utf-8") and
                // arrives in varying case — normalise before matching so a
                // group thread's plain-text part isn't missed and shown as
                // "[Picture message]".
                val contentType = (it.getString(1) ?: "").substringBefore(';').trim().lowercase()
                val hasDataFile = it.getString(3) != null
                when {
                    contentType == "application/smil" -> {
                        // Layout markup, never message content — skip it.
                    }
                    contentType == "text/plain" -> {
                        // The body is in the "text" column only when it isn't
                        // spilled to a part file; when "_data" is set the
                        // column is null and the text must be streamed from
                        // the part itself.
                        val inline = it.getString(2)
                        val body = if (!inline.isNullOrEmpty()) inline
                            else if (hasDataFile) readMmsPartText(partId) else null
                        if (!body.isNullOrEmpty()) textParts.add(body)
                    }
                    contentType.startsWith("image/") -> imageUri = "content://mms/part/$partId"
                    contentType == "text/x-vcard" || contentType == "text/vcard" ->
                        vcardUri = "content://mms/part/$partId"
                    contentType.startsWith("audio/") -> audioUri = "content://mms/part/$partId"
                    contentType.startsWith("video/") -> videoUri = "content://mms/part/$partId"
                    contentType.isNotEmpty() && fileUri == null -> {
                        // Anything else — PDF, document, unknown type.
                        fileUri = "content://mms/part/$partId"
                        fileMime = contentType
                        fileName = it.getString(4) ?: it.getString(6) ?: it.getString(5)
                    }
                }
            }
        }
        val text = textParts.joinToString("\n").trim()

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

        return MmsParts(text, imageUri, vcardUri, audioUri, senderAddress, videoUri, fileUri, fileMime, fileName)
    }

    /** Notification preview for a received MMS — its text, or a short
     *  description of what's attached. */
    fun mmsPreview(messageId: Long): String {
        val parts = readMmsParts(messageId)
        return when {
            parts.text.isNotEmpty() -> parts.text
            parts.imageUri != null -> "Picture message"
            parts.videoUri != null -> "Video"
            parts.audioUri != null -> "Voice message"
            parts.vcardUri != null -> "Contact card"
            parts.fileUri != null -> "File: ${parts.fileName ?: parts.fileMime}"
            else -> "Multimedia message"
        }
    }

    /** Reads a text/plain MMS part's body straight from the part's own
     *  stream — needed when the provider stored the text in a file
     *  ("_data" set) and left the "text" column null, which is common for
     *  the plain-text part of a group MMS. */
    private fun readMmsPartText(partId: Long): String? {
        return try {
            context.contentResolver.openInputStream(
                android.net.Uri.parse("content://mms/part/$partId")
            )?.use { it.readBytes().toString(Charsets.UTF_8) }
        } catch (e: Exception) {
            android.util.Log.w("TurboTextMms", "failed to read MMS text part $partId", e)
            null
        }
    }

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
            val projection = arrayOf(Telephony.Mms._ID, Telephony.Mms.DATE, Telephony.Mms.MESSAGE_BOX, Telephony.Mms.MESSAGE_TYPE)
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
                            vcardUri = parts.vcardUri, audioUri = parts.audioUri,
                            isUnretrievedMms = parts.text.isEmpty() && parts.imageUri == null &&
                                parts.vcardUri == null && parts.audioUri == null
                        )
                    )
                }
            }
        }

        return result.sortedByDescending { it.date }
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
            val requestCode = rowUri.lastPathSegment?.toIntOrNull() ?: System.currentTimeMillis().toInt()
            // One PendingIntent per part (the data URI differs by part
            // index, which keeps them distinct) so SmsSentReceiver hears
            // about every part and only marks the message Sent once all
            // of them went out — previously one shared PendingIntent meant
            // whichever part reported last decided the status.
            SmsSentReceiver.expectParts(rowUri.toString(), parts.size)
            val sentIntents = ArrayList<android.app.PendingIntent>()
            for (i in parts.indices) {
                val sentIntent = Intent(context, SmsSentReceiver::class.java).apply {
                    data = rowUri.buildUpon().appendQueryParameter("part", i.toString()).build()
                    putExtra("row_uri", rowUri.toString())
                    putExtra("thread_id", threadId ?: -1L)
                    putExtra(SmsSentReceiver.EXTRA_PART_INDEX, i)
                    putExtra(SmsSentReceiver.EXTRA_PART_COUNT, parts.size)
                }
                sentIntents.add(
                    android.app.PendingIntent.getBroadcast(
                        context, requestCode, sentIntent,
                        android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_MUTABLE
                    )
                )
            }

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
