package com.turbotext.app

import android.content.Context
import android.provider.ContactsContract

/**
 * Every saved number's contact name, keyed by its last 10 digits
 * (OwnNumber.key). Matching that way catches numbers the system's own
 * lookup misses when the carrier writes them differently from how
 * they're saved ("+15551234567" against "(555) 123-4567"), which left
 * some contacts in the report as bare numbers.
 */
class ContactIndex(private val names: Map<String, String>) {

    fun nameFor(address: String): String? = names[OwnNumber.key(address)]

    companion object {
        /** Reads all phone numbers once. Off the main thread. */
        fun load(context: Context): ContactIndex {
            val rows = ArrayList<Pair<String, String>>()
            try {
                context.contentResolver.query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    arrayOf(
                        ContactsContract.CommonDataKinds.Phone.NUMBER,
                        ContactsContract.CommonDataKinds.Phone.NORMALIZED_NUMBER,
                        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
                    ),
                    null, null, null
                )?.use { c ->
                    while (c.moveToNext()) {
                        val name = c.getString(2)?.takeIf { it.isNotBlank() } ?: continue
                        c.getString(0)?.let { rows.add(it to name) }
                        c.getString(1)?.let { rows.add(it to name) }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("TurboTextReport", "couldn't read contacts", e)
            }
            return from(rows)
        }

        /** (number as saved, name) pairs to an index; the first name
         *  saved for a number wins. */
        fun from(rows: List<Pair<String, String>>): ContactIndex {
            val names = LinkedHashMap<String, String>()
            for ((number, name) in rows) {
                val key = OwnNumber.key(number)
                if (key.length >= 7 && key !in names) names[key] = name
            }
            return ContactIndex(names)
        }
    }
}
