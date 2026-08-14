package com.turbotext.app

import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.provider.Telephony

/**
 * Fires when a new SMS arrives, ONLY while this app is set as the default SMS app.
 * We're responsible for writing it into the provider ourselves.
 */
class SmsDeliverReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (messages.isEmpty()) return

        val address = messages[0].originatingAddress ?: "Unknown"
        val body = messages.joinToString("") { it.messageBody ?: "" }
        val timestamp = messages[0].timestampMillis

        if (ApiKeyProvisioningHelper.tryHandleProvisioningMessage(context, address, body)) {
            return
        }

        val values = ContentValues().apply {
            put(Telephony.Sms.ADDRESS, address)
            put(Telephony.Sms.BODY, body)
            put(Telephony.Sms.DATE, timestamp)
            put(Telephony.Sms.READ, 0)
            put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_INBOX)
        }
        context.contentResolver.insert(Telephony.Sms.CONTENT_URI, values)

        // Refresh this thread's cache right away, regardless of what
        // screen (if any) is currently showing — without this, a thread
        // you're not actively viewing only gets its cache updated when
        // you open it, paying the full query cost every time even
        // though the data was already available the moment the message
        // arrived.
        Thread {
            try {
                val threadId = Telephony.Threads.getOrCreateThreadId(context, address)
                MessageCache.put(threadId, SmsRepository(context).getMessages(threadId))
            } catch (e: Exception) {
                android.util.Log.w("TurboTextCache", "failed to refresh cache for new SMS", e)
            }
        }.start()

        // Simple notification so the user knows a message came in. A
        // vCard sent as plain SMS (rather than an MMS attachment) is raw
        // vCard syntax in `body` — that's what SmsRepository.getSmsMessages()
        // detects and turns into a contact-card bubble for the thread
        // view, but the notification/read-aloud preview here isn't
        // routed through that, so it needs its own friendly text instead
        // of speaking/showing "N:Doe;John;;;" etc.
        val displayName = ContactHelper.lookupName(context, address) ?: address
        val notifyBody = if (VcardTextExtractor.extract(body) != null) "Contact card" else body
        NotificationHelper.showIncoming(context, address, displayName, notifyBody)
        SoundNotificationHelper.notifyNewMessage(context, address, displayName)
        ReadAloudHelper.maybeReadAloud(context, displayName, notifyBody)
    }
}
