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
    val vcardUri: String? = null,
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
