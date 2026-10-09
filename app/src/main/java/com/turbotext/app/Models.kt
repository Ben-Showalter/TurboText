package com.turbotext.app

data class Conversation(
    val threadId: Long,
    val address: String,
    val displayName: String,
    val snippet: String,
    val date: Long,
    val unread: Boolean
)

data class Message(
    val id: Long,
    val address: String,
    val body: String,
    val date: Long,
    val isOutgoing: Boolean,
    val isMms: Boolean = false,
    val imageUri: String? = null,
    /** Every picture in an MMS, in order — [imageUri] is the first. The
     *  bubble shows the first with a "+N" badge; the viewer pages
     *  through them all. */
    val imageUris: List<String> = listOfNotNull(imageUri),
    val vcardUri: String? = null,
    /** content:// URI of an audio (voice message) MMS part, when present. */
    val audioUri: String? = null,
    /** content:// URI of a video MMS part, when present. */
    val videoUri: String? = null,
    /** Any other attachment (PDF, document, unknown type) — shown as a
     *  file row and opened/saved by type. */
    val fileUri: String? = null,
    val fileMime: String? = null,
    val fileName: String? = null,
    /** An MMS whose content hasn't been downloaded (yet) — the provider
     *  only has the carrier's notification for it. */
    val mmsDownloadPending: Boolean = false,
    /** Who sent this specific message — only populated for incoming
     *  messages in a group thread, where it isn't otherwise obvious. */
    val senderName: String? = null,
    /** True when we know an MMS arrived but couldn't (yet) retrieve its
     *  contents — see README for why full MMS receiving is limited. */
    val isUnretrievedMms: Boolean = false,
    /** "sending", "sent", or "failed" — null for incoming messages, or
     *  for older sent messages from before this was tracked. Read from
     *  the SMS provider's own standard TYPE column (OUTBOX/SENT/FAILED),
     *  updated via SmsSentReceiver once SmsManager actually confirms
     *  the result. */
    val sendStatus: String? = null
)

data class BroadcastContact(val name: String, val number: String)

data class BroadcastList(val id: String, val name: String, val contacts: List<BroadcastContact>)
