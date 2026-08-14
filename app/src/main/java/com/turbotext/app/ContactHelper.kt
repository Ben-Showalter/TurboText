package com.turbotext.app

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract

/** Shared, cached phone-number -> contact-name lookup used by both the
 *  conversation list/thread UI and incoming-message notifications, so a
 *  number only needs to be resolved once. */
object ContactHelper {
    // ConcurrentHashMap throws on null values, but "no contact found" (null)
    // is an expected, common result here — a synchronized HashMap allows it.
    private val cache = java.util.Collections.synchronizedMap(HashMap<String, String?>())

    fun lookupName(context: Context, phoneNumber: String): String? {
        if (cache.containsKey(phoneNumber)) return cache[phoneNumber]

        val uri = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
            Uri.encode(phoneNumber)
        )
        val cursor = context.contentResolver.query(
            uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null
        )
        val name = cursor?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
        cache[phoneNumber] = name
        return name
    }

    /** Drops every cached lookup so the next call re-hits the provider.
     *  Needed because a number looked up before it's saved to contacts
     *  (or before an existing contact's name changes) would otherwise
     *  stay cached — including a cached miss — for the rest of the
     *  process's life. Called from a ContactsContract ContentObserver
     *  rather than anything more targeted, since we don't get told which
     *  number changed. */
    fun invalidateCache() {
        cache.clear()
    }
}
