package com.turbotext.app

import android.app.Notification
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.format.DateFormat
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.Date

/**
 * Posts a transient alert card to the Kyocera **cover (sub-LCD) screen** via the
 * OEM framework class `jp.kyocera.sublcd.SubLcdManager`, reached by reflection
 * on Android's public `Notification.Builder.extend()` API — the same extender
 * pattern wearable notifications use. This is the surface SystemUI's own PowerUI
 * uses for battery cards, and it is a *different* path from InfoSign →
 * `KeyguardStatusView`: it does not go through the notification-listener mirror
 * or the keyguard package allow-list that blocked a third-party app
 * (MatChat's docs/COVER-DISPLAY.md), so an ordinary app can post to it. This
 * is MatChat's CoverScreenNotifier, which grew out of TurboText's earlier
 * OuterScreenNotifier; the two apps now behave the same on the cover.
 *
 * Every card needs a duration and clears when it expires, so on top of the
 * single post this object:
 *  - **scrolls** text wider than the cover by reposting the same card id with
 *    a sliding window of the text (the OEM API has no marquee option —
 *    MatChat's probe result, docs/COVER-DISPLAY.md there);
 *  - **re-shows** the card whenever the cover turns on — flip closed, or an
 *    outside button pressed with the flip shut — via Kyocera's own callback
 *    ([CoverScreenCallback], MatChat's docs/adr/0009), with `SCREEN_OFF` (flip closed) as
 *    the fallback; until [cancel] is called when the conversation is read. It stops
 *    the card when the flip opens (`SCREEN_ON`).
 *
 * Ids are SoundNotificationHelper's conversation keys, hashed.
 *
 * All state is confined to the main thread. Every OEM call is reflection
 * wrapped in `runCatching`, so off Kyocera hardware this is a silent no-op.
 *
 * Never call `SubLcdManager.notifyIconToAnnunciatorTray`: on the DuraXV it
 * crashed SystemUI (MatChat's probe test, docs/COVER-DISPLAY.md there).
 */
object CoverScreenNotifier {

    const val TAG = "TurboTextCover"

    /** The sub-LCD rich-card template (`k_sublcd_template_5`) and
     *  `SubLcdManager.CATEGORY_INTERRUPT`, both confirmed by the probe. */
    private const val SUBLCD_LAYOUT_RICH_CARD = 0x010900af
    private const val SUBLCD_CATEGORY_MESSAGE = 2
    private const val SUBLCD_PRIORITY = 0

    /** How long a card stays up per showing. The sub-LCD holds the cover screen
     *  *on* for this whole span, so it is a battery/visibility trade-off —
     *  15 s per the user's call (re-shown on every flip close anyway). */
    private const val CARD_DURATION_MS = 15_000

    /** Characters visible beside the 96 px icon at once — longer text
     *  scrolls. Measured by MatChat on the DuraXV with its probe's ruler
     *  card (0–8 show); TurboText's icon is the same size. */
    private const val TICKER_WINDOW = 9

    /** Characters the ticker moves per frame (user's call: twice the old one). */
    private const val TICKER_SCROLL_STEP = 2

    /** Longest message the cover shows; anything past it is cut and ends in
     *  "…". High enough for the whole of an ordinary message (the user asked
     *  for the whole message), low enough that one pass (~50 s at
     *  [TICKER_STEP_MS]) doesn't hold the cover on for minutes. */
    private const val COVER_MAX_CHARS = 120
    private const val TICKER_STEP_MS = 400L

    /** Frames to hold still at the start and end of each scroll pass, so the
     *  beginning and the "…" are readable before it starts over. */
    private const val TICKER_PAUSE_STEPS = 3

    /** Each scroll frame outlasts the step, so the card stays up between
     *  frames and clears shortly after the last one. */
    private const val TICKER_HOLD_MS = 1_500

    private val main = Handler(Looper.getMainLooper())

    /** Cards still owed to the user (id → text), most recent last, until the
     *  conversation is read. Main thread only. */
    private val active = LinkedHashMap<Int, String>()
    private val tickers = HashMap<Int, Runnable>()
    private var screenReceiver: BroadcastReceiver? = null

    /** When the last card went up (elapsedRealtime), so a cover-on event
     *  doesn't restart a card that's still on screen. 0 = none up. */
    private var lastShownAt = 0L

    /** How long the last showing runs: [CARD_DURATION_MS], or longer when a
     *  scroll needs whole passes to finish (tickerFrameCount). */
    private var lastShowingMs = CARD_DURATION_MS.toLong()

    /** Shows (and keeps re-showing on flip close) a cover card for [id] with
     *  [text] until [cancel]. */
    fun notify(context: Context, id: Int, text: String) {
        val app = context.applicationContext
        main.post {
            active.remove(id)
            active[id] = text // move to most-recent
            startWatching(app)
            show(app, id, text)
        }
    }

    /** Clears the card for [id] and stops re-showing it (it was read). */
    fun cancel(context: Context, id: Int) {
        val app = context.applicationContext
        main.post {
            active.remove(id)
            stopTicker(id)
            cancelCard(app, id)
            lastShownAt = 0L
            if (active.isEmpty()) stopWatching(app)
        }
    }

    /** The cover just became visible (Kyocera's callback, or the flip closing):
     *  bring the latest unread card back, unless one is still up — our own
     *  post also wakes the cover, so this debounce is what stops a loop. */
    private fun maybeReshow(context: Context, reason: String) {
        val sinceShown = SystemClock.elapsedRealtime() - lastShownAt
        if (!shouldReshow(active.isNotEmpty(), sinceShown, lastShowingMs)) return
        val latest = active.entries.last()
        Log.d(TAG, "$reason — re-showing id=${latest.key}")
        show(context, latest.key, latest.value)
    }

    /** Only one card is on screen at a time: stop any other scroll, then show
     *  the time followed by [text] (cut to [COVER_MAX_CHARS]) — the card hides
     *  the cover's own clock, and its one text line is all the template draws
     *  (MatChat's docs/COVER-DISPLAY.md, "Clock on the card") — once if it fits, or as
     *  a ticker that scrolls to the end, pauses, and starts over from the
     *  beginning. The time is taken at each showing, so a re-show is current. */
    private fun show(context: Context, id: Int, text: String) {
        tickers.keys.toList().forEach(::stopTicker)
        lastShownAt = SystemClock.elapsedRealtime()
        val time = DateFormat.getTimeFormat(context).format(Date())
        val shown = coverLine(time, text, COVER_MAX_CHARS)
        if (shown.length <= TICKER_WINDOW) {
            lastShowingMs = CARD_DURATION_MS.toLong()
            post(context, id, shown, CARD_DURATION_MS)
            Log.d(TAG, "cover card shown id=$id scrolling=false")
            return
        }
        val cycle = tickerFrames(shown, TICKER_WINDOW, TICKER_PAUSE_STEPS, TICKER_SCROLL_STEP)
        // Whole passes only, so the card never ends partway through the
        // message: at least CARD_DURATION_MS, then the current pass finishes.
        val frames = tickerFrameCount(cycle.size, TICKER_STEP_MS, CARD_DURATION_MS.toLong())
        lastShowingMs = frames * TICKER_STEP_MS
        val ticker = object : Runnable {
            private var frame = 0
            override fun run() {
                // No PII: the id, pass number and frame counts only.
                if (frame % cycle.size == 0) {
                    Log.d(TAG, "cover pass ${frame / cycle.size + 1} id=$id frames=${cycle.size}")
                }
                post(context, id, cycle[frame % cycle.size], TICKER_HOLD_MS)
                frame++
                if (frame < frames) main.postDelayed(this, TICKER_STEP_MS) else tickers.remove(id)
            }
        }
        tickers[id] = ticker
        ticker.run()
        Log.d(TAG, "cover card shown id=$id scrolling=true")
    }

    private fun stopTicker(id: Int) {
        tickers.remove(id)?.let(main::removeCallbacks)
    }

    /** While any card is owed: Kyocera's callback (cover on — flip closed or
     *  an outside button) and SCREEN_OFF (flip closed; the fallback if the
     *  callback is unavailable) re-show the latest card; SCREEN_ON (flip
     *  opened) stops it, since the main screen shows the notification itself. */
    private fun startWatching(context: Context) {
        if (screenReceiver != null) return
        CoverScreenCallback.register(context) { main.post { maybeReshow(context, "cover on") } }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_SCREEN_OFF -> maybeReshow(context, "screen off (flip closed?)")
                    Intent.ACTION_SCREEN_ON -> {
                        Log.d(TAG, "screen on (flip opened?) — stopping cover cards")
                        active.keys.forEach { id ->
                            stopTicker(id)
                            cancelCard(context, id)
                        }
                        lastShownAt = 0L
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        screenReceiver = receiver
    }

    private fun stopWatching(context: Context) {
        CoverScreenCallback.unregister(context)
        screenReceiver?.let { runCatching { context.unregisterReceiver(it) } }
        screenReceiver = null
    }

    /** One post of one card. Logs only failures — a scrolling card posts every
     *  [TICKER_STEP_MS], so success lines would flood logcat. */
    @Suppress("DEPRECATION") // Notification.Builder(Context): this card is handed
    // to SubLcdManager, never posted through a NotificationManager channel.
    private fun post(context: Context, id: Int, text: String, durationMs: Int) {
        runCatching {
            val managerClass = Class.forName("jp.kyocera.sublcd.SubLcdManager")
            val manager = managerClass.getMethod("getInstance", Context::class.java).invoke(null, context)
            managerClass.getMethod("notify", Int::class.javaPrimitiveType, Notification::class.java)
                .invoke(manager, id, buildCard(context, text, durationMs))
        }.onFailure {
            // Expected on any non-Kyocera device (classes absent) or if the OEM
            // API differs — either way, safe to no-op.
            Log.d(TAG, "sub-LCD notify unavailable id=$id: ${it.javaClass.simpleName}")
        }
    }

    /** A Notification carrying the sub-LCD extender (icon, text, template,
     *  duration). The icon is the TurboText mark in black
     *  (drawable-nodpi/ic_cover_turbotext.png, tools/gen_cover_icon.py): the
     *  slot takes a resource id only, and a 96 px square drew best. */
    @Suppress("DEPRECATION")
    private fun buildCard(context: Context, text: String, durationMs: Int): Notification {
        val extenderClass = Class.forName("jp.kyocera.sublcd.SubLcdNotificationExtender")
        val extender = extenderClass.getConstructor(Context::class.java).newInstance(context)
        extenderClass.getMethod("setCategory", Int::class.javaPrimitiveType).invoke(extender, SUBLCD_CATEGORY_MESSAGE)
        extenderClass.getMethod("setLayout", Int::class.javaPrimitiveType).invoke(extender, SUBLCD_LAYOUT_RICH_CARD)
        extenderClass.getMethod("setPriority", Int::class.javaPrimitiveType).invoke(extender, SUBLCD_PRIORITY)
        extenderClass.getMethod("setIcon", Int::class.javaPrimitiveType).invoke(extender, R.drawable.ic_cover_turbotext)
        extenderClass.getMethod("setText", CharSequence::class.java).invoke(extender, text)
        extenderClass.getMethod("setDuration", Int::class.javaPrimitiveType).invoke(extender, durationMs)

        val builder = Notification.Builder(context)
            .setSmallIcon(R.drawable.ic_notification_minimal)
            .setContentText(text)
        Notification.Builder::class.java
            .getMethod("extend", Notification.Extender::class.java)
            .invoke(builder, extender)
        return builder.build()
    }

    private fun cancelCard(context: Context, id: Int) {
        runCatching {
            val managerClass = Class.forName("jp.kyocera.sublcd.SubLcdManager")
            val manager = managerClass.getMethod("getInstance", Context::class.java).invoke(null, context)
            managerClass.getMethod("cancel", Int::class.javaPrimitiveType).invoke(manager, id)
        }.onSuccess {
            Log.d(TAG, "sub-LCD cancel ok id=$id")
        }.onFailure {
            Log.d(TAG, "sub-LCD cancel unavailable id=$id: ${it.javaClass.simpleName}")
        }
    }
}

/** Whether the cover turning on should bring the card back: something is
 *  still unread, and the last showing has run its course (our own post also
 *  wakes the cover — this is what keeps that from looping). A pure function so
 *  the rule is unit-testable without a device. */
fun shouldReshow(
    hasPending: Boolean,
    msSinceLastShown: Long,
    cardDurationMs: Long,
): Boolean = hasPending && msSinceLastShown >= cardDurationMs

/** [text] as the cover shows it: unchanged up to [maxChars], otherwise cut to
 *  fit [maxChars] including a trailing "…". */
fun coverDisplayText(text: String, maxChars: Int): String =
    if (text.length <= maxChars) text else text.take(maxChars - 1).trimEnd() + "…"

/** The cover's one line: [time], then [text] cut by [coverDisplayText] to
 *  [maxChars] — e.g. "1:05 PM · Ann: see you at six". */
fun coverLine(time: String, text: String, maxChars: Int): String =
    "$time · ${coverDisplayText(text, maxChars)}"

/** Frames to post so a scroll runs at least [minDurationMs] and stops only
 *  at the end of a pass: the fewest whole passes of [passLength] frames
 *  ([stepMs] apart) that cover the minimum, and at least one. */
fun tickerFrameCount(passLength: Int, stepMs: Long, minDurationMs: Long): Int {
    val minFrames = ((minDurationMs + stepMs - 1) / stepMs).toInt()
    val passes = maxOf(1, (minFrames + passLength - 1) / passLength)
    return passes * passLength
}

/** One scroll pass over [text] as the frames the cover shows, [window]
 *  characters each: the start held for [pauseSteps] frames, [step] characters
 *  per frame to the end (the last frame always lands exactly on the end), the
 *  end held for [pauseSteps] frames. The ticker repeats this pass, so it
 *  always starts over cleanly from the beginning instead of wrapping the end
 *  into the start. Text that fits is a single frame. */
fun tickerFrames(text: String, window: Int, pauseSteps: Int, step: Int = 1): List<String> {
    if (text.length <= window) return listOf(text)
    val lastStart = text.length - window
    val moving = (step until lastStart step step).toList()
    val starts = List(pauseSteps) { 0 } + moving + List(pauseSteps) { lastStart }
    return starts.map { text.substring(it, it + window) }
}
