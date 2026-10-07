package com.turbotext.app

import android.content.Context
import android.net.Uri
import android.util.Log

/**
 * Called by mmslib once a downloaded MMS has been parsed and saved to the
 * system message store (already on a background thread). Hands the new
 * row to [MmsReceivePostProcessor] for TurboText's own side effects.
 *
 * Found by mmslib through its manifest taskAffinity
 * ("com.klinker.android.messaging.MMS_RECEIVED"), not an intent filter.
 */
class LibraryMmsReceivedReceiver : com.klinker.android.send_message.MmsReceivedReceiver() {

    override fun onMessageReceived(context: Context, messageUri: Uri) {
        Log.i("TurboTextMms", "MMS downloaded and saved: $messageUri")
        MmsReceivePostProcessor.process(context.applicationContext, messageUri)
    }

    override fun onError(context: Context, error: String) {
        Log.w("TurboTextMms", "MMS download error: $error")
        // Put the carrier's notice in the sender's thread (it's parked on
        // a contactless placeholder thread while downloading) so it shows
        // as "press OK to download" instead of an "Unknown" conversation.
        SmsRepository(context).rehomeOrphanedMmsNotifications()
        // Some phones save the MMS through the system service themselves
        // and report an error here anyway — look for it before giving up.
        MmsReceivePostProcessor.processRecentFallback(context.applicationContext)
    }
}
