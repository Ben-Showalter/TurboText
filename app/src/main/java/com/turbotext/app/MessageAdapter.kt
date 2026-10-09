package com.turbotext.app

import android.content.Context
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView

/**
 * Message bubbles for a conversation.
 *
 * Kept cheap to bind for smooth D-pad scrolling: moving the selection
 * repaints only the two rows involved (not every visible row), list
 * refreshes are diffed, bubble backgrounds are built once and reused,
 * and pictures are decoded at thumbnail size on a shared background
 * thread (see ThumbnailLoader).
 */
class MessageAdapter(private var items: List<Message>, private var hasMore: Boolean = false) :
    RecyclerView.Adapter<MessageAdapter.VH>() {

    companion object {
        /** Payload for "only the selection highlight changed". */
        private const val PAYLOAD_SELECTION = "sel"

        /** Corner radius of every bubble, text and photo alike. */
        private const val BUBBLE_RADIUS_DP = 12f

        /** The photo's margin inside its frame (item_message.xml). */
        private const val PHOTO_INSET_DP = 3f
    }

    /** Position in the *adapter's own* index space (including the Load
     *  More row, if present) — matches what LinearLayoutManager's
     *  findLastVisibleItemPosition() returns, which is what drives the
     *  scroll-based selection in ConversationActivity. */
    private var selectedPosition: Int? = null

    /** id of the voice-message row currently playing, so its bubble can
     *  show a ■ / "Playing…" state. Null when nothing is playing. */
    private var playingAudioId: Long? = null

    private var theme: AppTheme? = null
    private var attachedTo: RecyclerView? = null

    override fun onAttachedToRecyclerView(rv: RecyclerView) { attachedTo = rv }
    override fun onDetachedFromRecyclerView(rv: RecyclerView) { attachedTo = null }

    /** Adapter position the scroll bar should mark: the selected message
     *  while browsing, otherwise the newest one. */
    fun scrollbarPosition(): Int? = selectedPosition ?: (itemCount - 1).takeIf { it >= 0 }
    private var showAvatars = true

    /** Bubble background prototypes, one per (kind, side, selected)
     *  combination. Each view gets its own instance via newDrawable() —
     *  a single Drawable can't be shared between views of different
     *  sizes — but that's a cheap copy of already-built state rather
     *  than a fresh BubbleDrawable per bind. */
    private val bubbleProtos = HashMap<String, Drawable>()

    /** Bumped on a theme change so every row's cached background key
     *  goes stale and gets rebuilt in the new colors. */
    private var themeGeneration = 0

    /** Called by ConversationActivity when it starts/stops voice-message
     *  playback, so the bubble reflects it. Only the affected rows are
     *  rebound. */
    fun setPlayingAudioId(id: Long?) {
        if (playingAudioId == id) return
        val old = playingAudioId
        playingAudioId = id
        positionOfMessage(old)?.let { notifyItemChanged(it) }
        positionOfMessage(id)?.let { notifyItemChanged(it) }
    }

    private fun positionOfMessage(id: Long?): Int? {
        if (id == null) return null
        val i = items.indexOfFirst { it.id == id && it.audioUri != null }
        return if (i < 0) null else i + (if (hasMore) 1 else 0)
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val root: LinearLayout = view as LinearLayout
        val bubbleColumn: LinearLayout = view.findViewById(R.id.bubbleColumn)
        val avatar: ImageView = view.findViewById(R.id.senderAvatar)
        val text: TextView = view.findViewById(R.id.bubbleText)
        val time: TextView = view.findViewById(R.id.bubbleTime)
        val imageFrame: View = view.findViewById(R.id.bubbleImageFrame)
        val image: ImageView = view.findViewById(R.id.bubbleImage)
        val playOverlay: View = view.findViewById(R.id.playOverlay)
        val morePhotosBadge: TextView = view.findViewById(R.id.morePhotosBadge)

        /** Which bubble background each view currently has, so a rebind
         *  with the same look doesn't replace it. */
        var textBgKey: String? = null
        var imageBgKey: String? = null

        init {
            // Crops the bitmap itself to rounded corners (a background
            // alone wouldn't — it sits behind a rectangular image). The
            // selection border is drawn on imageFrame's own background.
            image.clipToOutline = true
            image.outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    // Inset by the frame's 3dp margin, so the photo's
                    // corners run parallel to the frame's 12dp ones.
                    val radius = (BUBBLE_RADIUS_DP - PHOTO_INSET_DP) * view.resources.displayMetrics.density
                    outline.setRoundRect(0, 0, view.width, view.height, radius)
                }
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_message, parent, false)
        return VH(view)
    }

    private fun themeFor(context: Context): AppTheme =
        theme ?: ThemeHelper.getCurrentTheme(context).also {
            theme = it
            showAvatars = SettingsHelper.isShowAvatars(context)
        }

    /** A rounded bubble — selection shows as a border around the
     *  bubble's own color. When [stripeOnLeft] is non-null, an accent
     *  stripe runs down that side, inside the same rounded outline. */
    private fun buildBubble(
        context: Context, theme: AppTheme, fillColor: Int, isSelected: Boolean, stripeOnLeft: Boolean?
    ): Drawable {
        val density = context.resources.displayMetrics.density
        return BubbleDrawable(
            BubbleDrawable.State(
                fillColor = fillColor,
                radius = BUBBLE_RADIUS_DP * density,
                stripeOnLeft = stripeOnLeft,
                stripeColor = theme.accentLight,
                stripeWidth = 5f * density,
                strokeColor = theme.accent,
                strokeWidth = if (isSelected) 2.5f * density else 0f,
            )
        )
    }

    private fun bubble(context: Context, kind: String, outgoing: Boolean?, selected: Boolean): Pair<String, Drawable> {
        val t = themeFor(context)
        val key = "$themeGeneration|$kind|$outgoing|$selected"
        val proto = bubbleProtos.getOrPut(key) {
            when (outgoing) {
                null -> buildBubble(context, t, t.surface, selected, null)
                true -> buildBubble(context, t, t.bubbleOutgoing, selected, stripeOnLeft = false)
                false -> buildBubble(context, t, t.bubbleIncoming, selected, stripeOnLeft = true)
            }
        }
        return key to (proto.constantState?.newDrawable(context.resources) ?: proto)
    }

    private fun setTextBubble(holder: VH, kind: String, outgoing: Boolean?, selected: Boolean) {
        val key = "$themeGeneration|$kind|$outgoing|$selected"
        if (holder.textBgKey == key) return
        holder.text.background = bubble(holder.itemView.context, kind, outgoing, selected).second
        holder.textBgKey = key
    }

    private fun setImageBubble(holder: VH, outgoing: Boolean, selected: Boolean) {
        val key = "$themeGeneration|image|$outgoing|$selected"
        if (holder.imageBgKey == key) return
        holder.imageFrame.background = bubble(holder.itemView.context, "image", outgoing, selected).second
        holder.imageBgKey = key
    }

    /** Sender name (incoming messages in a group thread) goes in accent
     *  color right next to the timestamp, e.g. "Sarah · 3:45 PM". */
    private fun buildTimeLabel(msg: Message, theme: AppTheme): CharSequence {
        val timeText = DateLabels.forMessage(msg.date)
        val statusWord = when (msg.sendStatus) {
            "sending" -> "Sending"
            "sent" -> "Sent"
            "delivered" -> "Delivered"
            "partially_delivered" -> "Delivered to ${msg.deliveredTo} of ${msg.recipientCount}"
            "not_delivered" -> "Not delivered"
            "failed" -> "Failed"
            else -> null
        }
        val senderName = msg.senderName

        if (senderName == null && statusWord == null) return timeText

        val sb = StringBuilder()
        var senderRange: IntRange? = null
        var statusRange: IntRange? = null

        if (senderName != null) {
            senderRange = 0 until senderName.length
            sb.append(senderName).append(" · ")
        }
        sb.append(timeText)
        if (statusWord != null) {
            sb.append(" · ")
            val start = sb.length
            sb.append(statusWord)
            statusRange = start until sb.length
        }

        val spannable = android.text.SpannableString(sb.toString())
        senderRange?.let {
            // Same color as the sender's avatar, so the two read as a pair.
            val color = if (showAvatars) AvatarLoader.colorFor(msg.address) else theme.accent
            spannable.setSpan(
                android.text.style.ForegroundColorSpan(color),
                it.first, it.last + 1, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        statusRange?.let {
            val failed = msg.sendStatus == "failed" || msg.sendStatus == "not_delivered"
            val color = if (failed) 0xFFCC3333.toInt() else theme.textSecondary
            spannable.setSpan(
                android.text.style.ForegroundColorSpan(color),
                it.first, it.last + 1, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        return spannable
    }

    override fun onBindViewHolder(holder: VH, position: Int, payloads: MutableList<Any>) {
        if (payloads.isNotEmpty() && payloads.all { it == PAYLOAD_SELECTION }) {
            bindSelection(holder, position)
        } else {
            onBindViewHolder(holder, position)
        }
    }

    /** Just the highlight border — the cheap path for D-pad moves. */
    private fun bindSelection(holder: VH, position: Int) {
        val isSelected = position == selectedPosition
        if (hasMore && position == 0) {
            setTextBubble(holder, "more", null, isSelected)
            return
        }
        val msg = items.getOrNull(if (hasMore) position - 1 else position) ?: return
        setTextBubble(holder, "text", msg.isOutgoing, isSelected)
        if (holder.imageFrame.visibility == View.VISIBLE) setImageBubble(holder, msg.isOutgoing, isSelected)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val context = holder.itemView.context
        val theme = themeFor(context)
        val isSelected = position == selectedPosition

        if (hasMore && position == 0) {
            holder.imageFrame.visibility = View.GONE
            holder.avatar.visibility = View.GONE
            holder.time.text = ""
            holder.text.visibility = View.VISIBLE
            holder.text.text = "Load More"
            holder.text.gravity = Gravity.CENTER
            holder.root.gravity = Gravity.CENTER
            holder.bubbleColumn.gravity = Gravity.CENTER
            setTextBubble(holder, "more", null, isSelected)
            holder.text.setTextColor(theme.accent)
            return
        }

        val msg = items[if (hasMore) position - 1 else position]
        holder.time.text = buildTimeLabel(msg, theme)
        holder.time.setTextColor(theme.textSecondary)

        // Default alignment for a plain text bubble — the voice-message
        // branch overrides this to CENTER, so reset it for recycled rows.
        holder.text.gravity = Gravity.START
        holder.playOverlay.visibility = View.GONE
        holder.morePhotosBadge.visibility = View.GONE

        when {
            msg.imageUri != null -> {
                holder.imageFrame.visibility = View.VISIBLE
                val px = (180 * context.resources.displayMetrics.density).toInt()
                ThumbnailLoader.load(holder.image, msg.imageUri, px)
                // The rest open in the viewer (OK, then Left/Right).
                if (msg.imageUris.size > 1) {
                    holder.morePhotosBadge.text = "+${msg.imageUris.size - 1}"
                    holder.morePhotosBadge.visibility = View.VISIBLE
                }
                holder.text.visibility = if (msg.body.isNotEmpty()) View.VISIBLE else View.GONE
                holder.text.text = msg.body
            }
            msg.videoUri != null -> {
                holder.imageFrame.visibility = View.VISIBLE
                holder.playOverlay.visibility = View.VISIBLE
                val px = (180 * context.resources.displayMetrics.density).toInt()
                ThumbnailLoader.load(holder.image, msg.videoUri, px, isVideo = true)
                holder.text.visibility = if (msg.body.isNotEmpty()) View.VISIBLE else View.GONE
                holder.text.text = msg.body
            }
            msg.audioUri != null -> {
                holder.imageFrame.visibility = View.GONE
                holder.text.visibility = View.VISIBLE
                holder.text.gravity = Gravity.CENTER
                val playing = playingAudioId == msg.id
                // ■ (stop) and ▶ (play) are plain geometric-shapes glyphs
                // that render on this device's stock font; ⏯/⏸ do not.
                val symbol = if (playing) "■" else "▶"
                val label = if (playing) "Playing… (OK to stop)" else "Voice message"
                val caption = if (msg.body.isNotEmpty()) "\n${msg.body}" else ""
                val span = android.text.SpannableString("$symbol\n$label$caption")
                span.setSpan(
                    android.text.style.RelativeSizeSpan(2.0f),
                    0, symbol.length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                holder.text.text = span
            }
            msg.fileUri != null -> {
                holder.imageFrame.visibility = View.GONE
                holder.text.visibility = View.VISIBLE
                val name = msg.fileName ?: msg.fileMime ?: "File"
                val caption = if (msg.body.isNotEmpty()) "\n${msg.body}" else ""
                holder.text.text = "📎 $name\nOK to open$caption"
            }
            msg.mmsDownloadPending -> {
                holder.imageFrame.visibility = View.GONE
                holder.text.visibility = View.VISIBLE
                holder.text.text = "⬇ Multimedia message — press OK to download"
            }
            msg.isUnretrievedMms -> {
                holder.imageFrame.visibility = View.GONE
                holder.text.visibility = View.VISIBLE
                holder.text.text = "⚠ Could not download and parse MMS"
            }
            msg.vcardUri != null -> {
                holder.imageFrame.visibility = View.GONE
                holder.text.visibility = View.VISIBLE
                holder.text.text = "📇 Contact card — see Options to import"
            }
            else -> {
                holder.imageFrame.visibility = View.GONE
                holder.text.visibility = View.VISIBLE
                holder.text.text = msg.body
            }
        }

        val gravity = if (msg.isOutgoing) Gravity.END else Gravity.START
        holder.root.gravity = gravity
        holder.bubbleColumn.gravity = gravity

        // Avatar next to incoming messages in group threads only — in a
        // one-to-one thread it'd just repeat the same face every row.
        if (showAvatars && !msg.isOutgoing && msg.senderName != null) {
            holder.avatar.visibility = View.VISIBLE
            AvatarLoader.bind(holder.avatar, msg.address, msg.senderName)
        } else {
            holder.avatar.visibility = View.GONE
        }

        // Stripe on the bubble's outer edge: right for outgoing, left for
        // incoming. The image frame gets the same fill, stripe and
        // selection border, so a picture reads as the same kind of bubble.
        setTextBubble(holder, "text", msg.isOutgoing, isSelected)
        holder.text.setTextColor(theme.bubbleText)
        if (holder.imageFrame.visibility == View.VISIBLE) setImageBubble(holder, msg.isOutgoing, isSelected)
    }

    override fun getItemCount() = items.size + (if (hasMore) 1 else 0)

    /** Replaces the full item list, rebinding only rows that changed.
     *  [hasMore] controls whether a Load More row renders at position 0. */
    fun update(newItems: List<Message>, hasMore: Boolean = false, context: Context? = null) {
        if (context != null) {
            val t = ThemeHelper.getCurrentTheme(context)
            val avatars = SettingsHelper.isShowAvatars(context)
            if (t != theme || avatars != showAvatars) {
                theme = t
                showAvatars = avatars
                bubbleProtos.clear()
                themeGeneration++
                items = newItems
                this.hasMore = hasMore
                notifyDataSetChanged()
                return
            }
        }
        val oldDisplay: List<Message?> = (if (this.hasMore) listOf<Message?>(null) else emptyList()) + items
        val newDisplay: List<Message?> = (if (hasMore) listOf<Message?>(null) else emptyList()) + newItems
        items = newItems
        this.hasMore = hasMore
        DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = oldDisplay.size
            override fun getNewListSize() = newDisplay.size
            override fun areItemsTheSame(o: Int, n: Int): Boolean {
                val a = oldDisplay[o]
                val b = newDisplay[n]
                if (a == null || b == null) return a == null && b == null
                return a.id == b.id && a.isMms == b.isMms
            }
            override fun areContentsTheSame(o: Int, n: Int) = oldDisplay[o] == newDisplay[n]
        }, false).dispatchUpdatesTo(this)
    }

    /** Prepends an older batch of messages (from "Load More"), keeping
     *  whatever's already loaded. */
    fun prepend(olderItems: List<Message>, hasMore: Boolean) {
        update(olderItems + items, hasMore)
    }

    /** Highlights the item at this *adapter* position (position 0 means
     *  the Load More row, when present) — or clears the highlight if
     *  null. Repaints only the old and new rows. */
    fun setSelectedPosition(position: Int?) {
        val old = selectedPosition
        if (old == position) return
        selectedPosition = position
        // The scroll bar's thumb follows the selection.
        attachedTo?.invalidate()
        if (old != null && old < itemCount) notifyItemChanged(old, PAYLOAD_SELECTION)
        if (position != null && position < itemCount) notifyItemChanged(position, PAYLOAD_SELECTION)
    }

    fun hasLoadMoreRow(): Boolean = hasMore

    /** Real messages only — excludes the synthetic Load More row. */
    fun currentItems(): List<Message> = items
}
