package com.turbotext.app

import android.app.Application

/** How many conversations' full message history gets eagerly loaded into
 *  MessageCache at startup. Bounded because this runs sequentially and a
 *  real log capture on a low-end device (Kyocera E4610, 157 conversations)
 *  showed it still running 90+ seconds in, contending with real usage. */
private const val PREWARM_THREAD_LIMIT = 25

/**
 * Parsing the ~3,000-word T9 dictionary takes real time on this phone's
 * low-end CPU. It's cached after first use (see T9EngineHolder), but that
 * first parse was happening on the main thread inside ConversationActivity,
 * which is exactly when you're waiting for the thread screen to appear —
 * a measured ~3 second stall. Kicking it off here, in the background, the
 * moment the process starts gives it a head start before you've even
 * finished looking at the conversation list.
 */
class TurboTextApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        // Contact saves/edits happen in the system Contacts app, outside
        // our control — this is the only way to learn a name changed, so
        // ContactHelper's cached lookups (otherwise stuck for the life of
        // the process, see ContactHelper.invalidateCache()) get dropped
        // and the conversation list picks up the new name on its next
        // (already-existing) resume-triggered refresh.
        //
        // Guarded on the permission because Application.onCreate() runs
        // before MainActivity's runtime permission request ever shows —
        // on a fresh install, registering unconditionally throws
        // SecurityException here and kills the process before the user
        // can grant anything. Skipping it just means this observer picks
        // up on the next launch, once READ_CONTACTS has been granted.
        if (androidx.core.content.ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.READ_CONTACTS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            contentResolver.registerContentObserver(
                android.provider.ContactsContract.Contacts.CONTENT_URI,
                true,
                object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
                    override fun onChange(selfChange: Boolean) {
                        ContactHelper.invalidateCache()
                    }
                }
            )
        }

        Thread {
            SmsRepository(this).purgeExpiredTrash()
        }.start()

        Thread {
            DefaultNotificationSounds.ensureDefaultsExist(SoundNotificationHelper.soundsDirectory())
        }.start()

        Thread {
            T9EngineHolder.get(this)
        }.start()

        // Voice-to-text uses Groq's cloud Whisper API — no on-device model
        // to pre-warm.

        // Logcat timing showed a ~2.7s delay opening a conversation that was
        // ALMOST IDENTICAL whether fetching 12 messages or the full 240 —
        // meaning the cost is a fixed, one-time tax on this app's first
        // touch of the SMS/MMS content provider in this run (likely a cold
        // Binder connection / database-open cost), not something that
        // scales with how much is queried. A trickle-loading fix couldn't
        // have helped that. Paying this cost here, invisibly, at app
        // startup means it's very likely already paid by the time you
        // actually open a conversation.
        Thread {
            // A real log capture on a Kyocera E4610 showed this whole
            // thread — 157 conversations' worth of full message history,
            // loaded sequentially — still running 90+ seconds after
            // launch, and contending badly enough with real foreground
            // work (opening a thread, sending a message) that an SMS send
            // stalled for 39 seconds waiting on the provider. Background
            // priority so the scheduler favors the interactive thread
            // whenever both want the CPU at once.
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            try {
                android.util.Log.i("TurboTextPerf", "provider warm-up started")
                val t0 = System.currentTimeMillis()
                contentResolver.query(
                    android.provider.Telephony.Sms.CONTENT_URI,
                    arrayOf(android.provider.Telephony.Sms._ID),
                    null, null, "date DESC LIMIT 1"
                )?.close()
                android.util.Log.i("TurboTextPerf", "sms warm-up done: ${System.currentTimeMillis() - t0}ms")
                val t1 = System.currentTimeMillis()
                // Mms is a separate provider authority from Sms — appears
                // to carry its own separate connection cost, unwarmed by
                // the query above, which would explain a real delay
                // remaining even after this fix went in for Sms alone.
                contentResolver.query(
                    android.provider.Telephony.Mms.CONTENT_URI,
                    arrayOf(android.provider.Telephony.Mms._ID),
                    null, null, "date DESC LIMIT 1"
                )?.close()
                android.util.Log.i("TurboTextPerf", "mms warm-up done: ${System.currentTimeMillis() - t1}ms")
            } catch (e: Exception) {
                android.util.Log.w("TurboTextPerf", "provider warm-up failed (harmless, just means no head start)", e)
            }

            // The connection cost above is a one-time tax, not a per-query
            // one — so once it's paid, proactively caching every thread's
            // messages here means opening ANY conversation later (not just
            // ones already visited this session) shows instantly. Most
            // recent first, since that's what's most likely to be opened
            // next, in case the user starts navigating before this
            // finishes — sequential rather than concurrent, to avoid
            // contending with whatever query the user's own navigation
            // triggers at the same time.
            val conversations = try {
                val repo = SmsRepository(this)
                val list = repo.getConversations()
                // Published immediately — MainActivity's first load can
                // pick this up the moment it's ready, rather than waiting
                // for the message-prewarm loop below to finish too.
                ConversationPrewarm.publish(list)
                ConversationListCache.put(list)
                list
            } catch (e: Exception) {
                android.util.Log.w("TurboTextPerf", "conversation prewarm fetch failed (harmless, just means no head start)", e)
                // Unblocks MainActivity's first-load wait immediately
                // instead of leaving it stuck until the timeout — a
                // failed prewarm just means it falls through to its own
                // normal query, same as if this thread never existed.
                ConversationPrewarm.publish(emptyList())
                emptyList()
            }
            try {
                val repo = SmsRepository(this)
                val t2 = System.currentTimeMillis()
                var count = 0
                // Capped rather than prewarming every conversation — on a
                // fast device 157 sequential full-history loads was just
                // slow; on the E4610 it meant real usage (opening a
                // thread, sending a message) queued up behind the
                // provider for a minute-plus. Conversations beyond this
                // cap still open fine, just via a normal (not instant)
                // load instead of a cache hit. Already sorted most-recent
                // first, so this keeps the head start where it matters.
                for (convo in conversations.take(PREWARM_THREAD_LIMIT)) {
                    MessageCache.put(convo.threadId, repo.getMessages(convo.threadId))
                    count++
                }
                android.util.Log.i("TurboTextPerf", "prewarmed $count conversation threads: ${System.currentTimeMillis() - t2}ms")

                val t3 = System.currentTimeMillis()
                val groups = repo.getGroupThreads()
                GroupThreadsCache.put(groups)
                for (group in groups.take(PREWARM_THREAD_LIMIT)) {
                    MessageCache.put(group.threadId, repo.getMessages(group.threadId))
                }
                android.util.Log.i("TurboTextPerf", "prewarmed ${minOf(groups.size, PREWARM_THREAD_LIMIT)} group threads: ${System.currentTimeMillis() - t3}ms")
            } catch (e: Exception) {
                android.util.Log.w("TurboTextPerf", "thread prewarm failed (harmless, just means no head start)", e)
            }
        }.start()
    }
}
