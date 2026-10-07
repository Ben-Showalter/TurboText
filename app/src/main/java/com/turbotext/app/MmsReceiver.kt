package com.turbotext.app

/**
 * Incoming-MMS entry point (WAP_PUSH_DELIVER). All of the work — parsing
 * the carrier's notification, de-duplicating repeats, fixing up the
 * download URL, and asking the phone's MMS service to download it — is
 * done by mmslib's PushReceiver (AOSP code). When the download finishes,
 * [LibraryMmsReceivedReceiver] takes over.
 *
 * Replaces TurboText's own MmsDeliverReceiver / MmsPduParser /
 * MmsRetrieveParser, which hand-parsed the PDUs.
 */
class MmsReceiver : com.android.mms.transaction.PushReceiver()
