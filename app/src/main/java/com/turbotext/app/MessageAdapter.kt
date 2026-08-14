package com.turbotext.app

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.view.LayoutInflater
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.text.DateFormat

class MessageAdapter(private var items: List<Message>, private var hasMore: Boolean = false) :
    RecyclerView.Adapter<MessageAdapter.VH>() {

    /** Position in the *adapter's own* index space (including the Load
     *  More row, if present) — matches what LinearLayoutManager's
     *  findLastVisibleItemPosition() returns, which is what drives the
     *  scroll-based selection in ConversationActivity. */
    private var selectedPosition: Int? = null

    // Small cache so scrolling back over an already-decoded image doesn't
    // pay the decode cost again — a handful of thumbnails is trivial
    // memory, and this device's decode is slow enough that avoiding
    // repeats matters.
    private val bitmapCache = android.util.LruCache<String, android.graphics.Bitmap>(20)

    class VH(view: android.view.View) : RecyclerView.ViewHolder(view) {
        val root: LinearLayout = view as LinearLayout
        val text: TextView = view.findViewById(R.id.bubbleText)
        val time: TextView = view.findViewById(R.id.bubbleTime)
        val imageFrame: View = view.findViewById(R.id.bubbleImageFrame)
        val image: ImageView = view.findViewById(R.id.bubbleImage)

        init {
            // Crops the bitmap itself to rounded corners (a background
            // alone wouldn't do this — it just sits behind a rectangular
            // image that would still show square corners past it). The
            // selection border is drawn separately, on imageFrame's own
            // background, in bubbleDrawable() below.
            image.clipToOutline = true
            image.outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    val radius = 17f * view.resources.displayMetrics.density
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

    /** A rounded bubble instead of a plain rectangle — selection shows as
     *  a border around the bubble's own color rather than replacing it
     *  with a solid fill. When [stripeOnLeft] is non-null, an accent
     *  stripe is baked into the same shape on that side (rather than
     *  added as a separate view, which was pushing the whole bubble
     *  further toward the screen edge than intended). LayerDrawable's
     *  setLayerGravity/setLayerWidth pin the stripe to a fixed width on
     *  one side regardless of the bubble's own width, which isn't known
     *  until after layout since it's wrap_content. */
    private fun bubbleDrawable(
        context: Context, fillColor: Int, isSelected: Boolean, stripeOnLeft: Boolean? = null
    ): android.graphics.drawable.Drawable {
        val theme = ThemeHelper.getCurrentTheme(context)
        val density = context.resources.displayMetrics.density
        val cornerRadius = 20f * density

        val base = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadii = when (stripeOnLeft) {
                null -> floatArrayOf(
                    cornerRadius, cornerRadius, cornerRadius, cornerRadius,
                    cornerRadius, cornerRadius, cornerRadius, cornerRadius
                )
                // Sharp on the side the bar sits, rounded on the other.
                true -> floatArrayOf(0f, 0f, cornerRadius, cornerRadius, cornerRadius, cornerRadius, 0f, 0f)
                false -> floatArrayOf(cornerRadius, cornerRadius, 0f, 0f, 0f, 0f, cornerRadius, cornerRadius)
            }
            setColor(fillColor)
            if (isSelected) {
                setStroke((2.5f * density).toInt(), theme.accent)
            }
        }
        if (stripeOnLeft == null) return base

        val stripeWidthPx = (5f * density).toInt()
        val stripe = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(theme.accentLight)
            // Plain square corners now — matches the base bubble's now-
            // sharp edge on this side, avoiding a mismatch where a
            // rounded stripe would recede from a sharp corner and let the
            // base color peek through underneath it.
        }

        val layered = android.graphics.drawable.LayerDrawable(arrayOf(base, stripe))
        layered.setLayerGravity(1, if (stripeOnLeft) Gravity.START else Gravity.END)
        layered.setLayerWidth(1, stripeWidthPx)
        return layered
    }

    /** Sender name (when present — incoming messages in a group thread)
     *  goes in accent color right next to the timestamp, e.g. "Sarah ·
     *  3:45 PM". */
    private fun buildTimeLabel(msg: Message, theme: AppTheme): CharSequence {
        val timeText = DateFormat.getTimeInstance(DateFormat.SHORT).format(msg.date)
        val statusWord = when (msg.sendStatus) {
            "sending" -> "Sending"
            "sent" -> "Sent"
            "delivered" -> "Delivered"
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
            spannable.setSpan(
                android.text.style.ForegroundColorSpan(theme.accent),
                it.first, it.last + 1, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        statusRange?.let {
            val color = if (msg.sendStatus == "failed") 0xFFCC3333.toInt() else theme.textSecondary
            spannable.setSpan(
                android.text.style.ForegroundColorSpan(color),
                it.first, it.last + 1, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        return spannable
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val context = holder.itemView.context
        val theme = ThemeHelper.getCurrentTheme(context)
        val isSelected = position == selectedPosition

        if (hasMore && position == 0) {
            holder.imageFrame.visibility = View.GONE
            holder.time.text = ""
            holder.text.visibility = View.VISIBLE
            holder.text.text = "Load More"
            holder.root.gravity = Gravity.CENTER
            holder.text.background = bubbleDrawable(context, theme.surface, isSelected)
            holder.text.setTextColor(theme.accent)
            return
        }

        val msg = items[if (hasMore) position - 1 else position]
        holder.time.text = buildTimeLabel(msg, theme)

        when {
            msg.imageUri != null -> {
                holder.imageFrame.visibility = View.VISIBLE
                val uriString = msg.imageUri
                holder.image.tag = uriString
                val cached = bitmapCache.get(uriString)
                if (cached != null) {
                    holder.image.setImageBitmap(cached)
                } else {
                    holder.image.setImageDrawable(null)
                    val context = holder.itemView.context
                    Thread {
                        try {
                            val bitmap = context.contentResolver.openInputStream(Uri.parse(uriString))
                                ?.use { android.graphics.BitmapFactory.decodeStream(it) }
                            if (bitmap != null) {
                                bitmapCache.put(uriString, bitmap)
                                holder.image.post {
                                    // Guards against this row having been
                                    // recycled for a different message by
                                    // the time the decode finishes.
                                    if (holder.image.tag == uriString) {
                                        holder.image.setImageBitmap(bitmap)
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            android.util.Log.w("TurboTextPerf", "async image decode failed", e)
                        }
                    }.start()
                }
                holder.text.visibility = if (msg.body.isNotEmpty()) View.VISIBLE else View.GONE
                holder.text.text = msg.body
            }
            msg.isUnretrievedMms -> {
                holder.imageFrame.visibility = View.GONE
                holder.text.visibility = View.VISIBLE
                holder.text.text = "[Picture message — see README]"
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

        val fillColor = if (msg.isOutgoing) theme.bubbleOutgoing else theme.bubbleIncoming
        // Stripe sits on the bubble's outer edge: right side for outgoing
        // (which is itself right-aligned), left side for incoming. The
        // image frame gets the exact same treatment as the text bubble —
        // same fill, same stripe, same selection border — so a picture
        // message reads as the same kind of bubble as a text one.
        holder.text.background = bubbleDrawable(context, fillColor, isSelected, stripeOnLeft = !msg.isOutgoing)
        holder.text.setTextColor(theme.bubbleText)
        holder.imageFrame.background = bubbleDrawable(context, fillColor, isSelected, stripeOnLeft = !msg.isOutgoing)
    }

    override fun getItemCount() = items.size + (if (hasMore) 1 else 0)

    /** Replaces the full item list. [hasMore] controls whether a Load
     *  More row renders at position 0. */
    fun update(newItems: List<Message>, hasMore: Boolean = false) {
        items = newItems
        this.hasMore = hasMore
        notifyDataSetChanged()
    }

    /** Prepends an older batch of messages (from "Load More"), keeping
     *  whatever's already loaded. */
    fun prepend(olderItems: List<Message>, hasMore: Boolean) {
        items = olderItems + items
        this.hasMore = hasMore
        notifyDataSetChanged()
    }

    /** Highlights the item at this *adapter* position (position 0 means
     *  the Load More row, when present) — or clears the highlight if
     *  null. */
    fun setSelectedPosition(position: Int?) {
        selectedPosition = position
        notifyDataSetChanged()
    }

    fun hasLoadMoreRow(): Boolean = hasMore

    /** Real messages only — excludes the synthetic Load More row. */
    fun currentItems(): List<Message> = items
}
