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
class ContactIndex(
    private val names: Map<String, String>,
    /** Last 7 digits -> name, only where one name has that ending: for
     *  contacts saved without the area code ("555-1234"). */
    private val local: Map<String, String> = emptyMap()
) {

    fun nameFor(address: String): String? {
        val key = OwnNumber.key(address)
        if (key.length < 7) return null
        return names[key] ?: local[key.takeLast(7)]
    }

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
            val local = HashMap<String, String?>()
            for ((number, name) in rows) {
                val key = OwnNumber.key(number)
                if (key.length < 7) continue
                if (key !in names) names[key] = name
                // A 7-digit ending shared by two different names is
                // ambiguous: leave it out (null) rather than guess.
                val tail = key.takeLast(7)
                if (tail !in local) local[tail] = name
                else if (local[tail] != name) local[tail] = null
            }
            @Suppress("UNCHECKED_CAST")
            return ContactIndex(names, local.filterValues { it != null } as Map<String, String>)
        }

        /** A number with no contact, written the usual US way —
         *  "(555) 123-4567" — instead of "+15551234567". Anything that
         *  isn't a 10-digit number (with or without +1) is left as is. */
        fun formatNumber(address: String): String {
            val trimmed = address.trim()
            if (trimmed.any { !(it.isDigit() || it in " -.()+") }) return address
            var digits = trimmed.filter { it.isDigit() }
            if (digits.length == 11 && digits.startsWith("1")) digits = digits.substring(1)
            if (digits.length != 10) return address
            return "(${digits.substring(0, 3)}) ${digits.substring(3, 6)}-${digits.substring(6)}"
        }
    }
}
