package com.turbotext.app

import android.content.Context
import android.provider.Telephony
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log
import org.json.JSONArray

/**
 * This phone's own number(s), so a received group message's thread
 * leaves us out. mmslib only asked TelephonyManager.getLine1Number(),
 * which is often empty on these phones: our number then stayed in the
 * group, so everyone else's messages went to one thread ({them + us})
 * and ours to another ({them}) — half the conversation seemed to vanish.
 *
 * Sources, in order: the line number, the SIM subscriptions' numbers,
 * and a number learned from received group messages (see [learnFrom]).
 * [publish] hands them to mmslib (PduPersister.setOwnNumbers); when a
 * number is first learned, [GroupThreadRepair] moves messages that
 * already landed in a thread with us in it back where they belong.
 */
object OwnNumber {
    private const val TAG = "TurboTextOwnNumber"
    private const val PREFS = "own_number"
    private const val KEY_LEARNED = "learned"
    private const val KEY_HISTORY = "group_to_lists"
    /** Like KEY_HISTORY, with each message's sender: {"f": key, "r": [keys]}. */
    private const val KEY_HISTORY_V2 = "group_messages"
    private const val KEY_REPAIRED = "repaired_for"

    /** Received group messages to remember the recipients of. */
    private const val HISTORY_SIZE = 10

    /** Group messages a number must be in (all of them) to count as ours. */
    private const val MIN_GROUP_MESSAGES = 3

    /** Last 10 digits — how numbers are compared here. */
    fun key(number: String): String = number.filter { it.isDigit() }.takeLast(10)

    fun known(context: Context): Set<String> {
        val numbers = LinkedHashSet<String>()
        try {
            val tm = context.getSystemService(TelephonyManager::class.java)
            @Suppress("DEPRECATION", "HardwareIds")
            tm?.line1Number?.takeIf { it.isNotBlank() }?.let { numbers.add(it) }
        } catch (e: Exception) {
            // No phone-state permission yet.
        }
        try {
            val sm = context.getSystemService(SubscriptionManager::class.java)
            @Suppress("DEPRECATION")
            sm?.activeSubscriptionInfoList?.forEach { info ->
                info.number?.takeIf { it.isNotBlank() }?.let { numbers.add(it) }
            }
        } catch (e: Exception) {
            // Same.
        }
        prefs(context).getString(KEY_LEARNED, null)?.let { numbers.add(it) }
        return numbers
    }

    /** Our numbers for the log: each one's source and last 4 digits only. */
    fun describe(context: Context): String {
        val parts = ArrayList<String>()
        try {
            val tm = context.getSystemService(TelephonyManager::class.java)
            @Suppress("DEPRECATION", "HardwareIds")
            tm?.line1Number?.takeIf { it.isNotBlank() }?.let { parts.add("line:" + last4(it)) }
        } catch (e: Exception) {
        }
        try {
            val sm = context.getSystemService(SubscriptionManager::class.java)
            @Suppress("DEPRECATION")
            sm?.activeSubscriptionInfoList?.forEach { info ->
                info.number?.takeIf { it.isNotBlank() }?.let { parts.add("sim:" + last4(it)) }
            }
        } catch (e: Exception) {
        }
        prefs(context).getString(KEY_LEARNED, null)?.let { parts.add("learned:" + last4(it)) }
        return if (parts.isEmpty()) "none" else parts.joinToString(" ")
    }

    fun last4(number: String): String = number.filter { it.isDigit() }.takeLast(4).ifEmpty { "?" }

    /**
     * Forgets a learned number that has since sent us something — then it
     * was a group member, not us, and leaving it out of group threads made
     * that person's group messages land somewhere else. Earlier versions
     * could learn one from a run of messages all sent by the same person.
     * Off the main thread.
     */
    fun validateLearned(context: Context) {
        val prefs = prefs(context)
        val learned = prefs.getString(KEY_LEARNED, null) ?: return
        try {
            if (!hasSentUs(context, key(learned))) return
            prefs.edit()
                .remove(KEY_LEARNED)
                .remove(KEY_HISTORY)
                .remove(KEY_HISTORY_V2)
                .putStringSet(KEY_REPAIRED, (prefs.getStringSet(KEY_REPAIRED, emptySet()) ?: emptySet()) - key(learned))
                .apply()
            Log.w(TAG, "learned own number ..${last4(learned)} has sent us messages: forgotten")
            publish(context)
        } catch (e: Exception) {
            Log.w(TAG, "couldn't check the learned number", e)
        }
    }

    /** Clears a learned number (Settings > Advanced). */
    fun forgetLearned(context: Context) {
        prefs(context).edit().remove(KEY_LEARNED).remove(KEY_HISTORY).remove(KEY_HISTORY_V2).apply()
        publish(context)
    }

    /** Repairs the threads (GroupThreadRepair) once for each of our
     *  numbers, as each becomes known — messages split before this
     *  version, or before the number was learned. Off the main thread. */
    fun repairOnce(context: Context) {
        val prefs = prefs(context)
        val done = prefs.getStringSet(KEY_REPAIRED, emptySet()) ?: emptySet()
        val todo = known(context).map(::key).filter { it.isNotEmpty() && it !in done }.distinct()
        if (todo.isEmpty()) return
        todo.forEach { GroupThreadRepair.run(context, it) }
        prefs.edit().putStringSet(KEY_REPAIRED, done + todo).apply()
    }

    /** Tells mmslib the numbers that are ours. Cheap; call at startup. */
    fun publish(context: Context) {
        val numbers = known(context)
        com.google.android.mms.pdu_alt.PduPersister.setOwnNumbers(numbers)
        Log.i(TAG, "own numbers known: ${numbers.size}")
    }

    /**
     * Learns our number from a received MMS ([mmsId]), when nothing else
     * gave it: it's the one number that's a recipient of every received
     * group message ([MIN_GROUP_MESSAGES] or more) and has never sent us
     * anything. Repairs the threads the first time it's learned. Off the
     * main thread.
     */
    fun learnFrom(context: Context, mmsId: Long) {
        try {
            if (known(context).isNotEmpty()) return
            val recipients = addresses(context, mmsId, "151, 130").map(::key).filter { it.isNotEmpty() }.toSet()
            if (recipients.size < 2) return // not a group message

            val sender = addresses(context, mmsId, "137").firstOrNull()?.let(::key).orEmpty()

            val prefs = prefs(context)
            val history = JSONArray(prefs.getString(KEY_HISTORY_V2, "[]"))
            val samples = (0 until history.length()).map { i ->
                val o = history.getJSONObject(i)
                val r = o.getJSONArray("r")
                Sample(o.optString("f"), (0 until r.length()).map { r.getString(it) }.toSet())
            }.plusElement(Sample(sender, recipients)).takeLast(HISTORY_SIZE)
            prefs.edit().putString(
                KEY_HISTORY_V2,
                JSONArray(samples.map { org.json.JSONObject().put("f", it.from).put("r", JSONArray(it.to.toList())) }).toString()
            ).apply()

            val candidate = pickFrom(samples, MIN_GROUP_MESSAGES) ?: return
            if (hasSentUs(context, candidate)) return
            val number = addresses(context, mmsId, "151, 130").first { key(it) == candidate }
            prefs.edit().putString(KEY_LEARNED, number).apply()
            Log.i(TAG, "learned own number from group messages")
            publish(context)
            repairOnce(context)
        } catch (e: Exception) {
            Log.w(TAG, "couldn't learn own number", e)
        }
    }

    /** One received group message: its sender and its To/Cc numbers. */
    data class Sample(val from: String, val to: Set<String>)

    /** [pick] over [samples], stricter: they must come from at least two
     *  different senders (with one sender, the other members are in every
     *  list too), and the number must never be one of the senders. */
    fun pickFrom(samples: List<Sample>, minLists: Int): String? {
        val senders = samples.map { it.from }.filter { it.isNotEmpty() }.toSet()
        if (senders.size < 2) return null
        val candidate = pick(samples.map { it.to }, minLists) ?: return null
        return candidate.takeIf { it !in senders }
    }

    /** The one number in every list, once there are at least [minLists]
     *  of them; null when there's none or more than one. */
    fun pick(lists: List<Set<String>>, minLists: Int): String? {
        if (lists.size < minLists) return null
        val common = lists.reduce { acc, s -> acc intersect s }
        return common.singleOrNull()
    }

    /** True when [numberKey] has ever sent us a text or picture message —
     *  then it's someone else, however many groups they're in. */
    private fun hasSentUs(context: Context, numberKey: String): Boolean {
        val like = arrayOf("%$numberKey")
        val sms = context.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI, arrayOf(Telephony.Sms._ID),
            "${Telephony.Sms.ADDRESS} LIKE ?", like, null
        )?.use { it.count } ?: 0
        if (sms > 0) return true
        // MMS senders: the FROM (137) address of received messages.
        val mms = context.contentResolver.query(
            Telephony.Mms.Inbox.CONTENT_URI, arrayOf(Telephony.Mms._ID),
            null, null, "${Telephony.Mms.DATE} DESC LIMIT 200"
        )?.use { c ->
            var found = false
            while (!found && c.moveToNext()) {
                found = addresses(context, c.getLong(0), "137").any { key(it) == numberKey }
            }
            found
        } ?: false
        return mms
    }

    private fun addresses(context: Context, mmsId: Long, types: String): List<String> =
        context.contentResolver.query(
            android.net.Uri.parse("content://mms/$mmsId/addr"),
            arrayOf("address"), "type IN ($types)", null, null
        )?.use { c ->
            val list = ArrayList<String>()
            while (c.moveToNext()) c.getString(0)?.let { list.add(it) }
            list
        } ?: emptyList()

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/**
 * Moves messages out of group threads that wrongly include our own
 * number into the thread of everyone else in them — the thread our own
 * replies already go to — so the whole conversation is in one place.
 */
object GroupThreadRepair {
    private const val TAG = "TurboTextOwnNumber"

    fun run(context: Context, ownKey: String) {
        val repo = SmsRepository(context)
        var moved = 0
        try {
            for ((threadId, participants) in repo.allThreadParticipants()) {
                if (participants.size < 2) continue
                val rest = participants.filter { OwnNumber.key(it) != ownKey }
                if (rest.size == participants.size || rest.isEmpty()) continue
                val target = Telephony.Threads.getOrCreateThreadId(context, rest.toSet())
                if (target == threadId) continue
                val values = android.content.ContentValues().apply { put(Telephony.Mms.THREAD_ID, target) }
                moved += context.contentResolver.update(
                    Telephony.Mms.CONTENT_URI, values, "${Telephony.Mms.THREAD_ID} = ?", arrayOf(threadId.toString())
                )
                val smsValues = android.content.ContentValues().apply { put(Telephony.Sms.THREAD_ID, target) }
                moved += context.contentResolver.update(
                    Telephony.Sms.CONTENT_URI, smsValues, "${Telephony.Sms.THREAD_ID} = ?", arrayOf(threadId.toString())
                )
                MessageCache.remove(threadId)
                MessageCache.remove(target)
            }
        } catch (e: Exception) {
            Log.w(TAG, "group thread repair failed", e)
        }
        Log.i(TAG, "group thread repair moved $moved message(s)")
        if (moved > 0) {
            ConversationListCache.clear()
            GroupThreadsCache.clear()
            MessageEvents.changed(null)
        }
    }
}
