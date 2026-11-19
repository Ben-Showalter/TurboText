package com.turbotext.app

import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator

/**
 * The conversation's only animation: a new message at the bottom (one
 * you send, or one that arrives) slides up into place and fades in, over
 * [DURATION_MS]. The adapter marks which rows are new
 * ([MessageAdapter.VH.slideIn]). Everything else — refreshes, Sending →
 * Sent, removals, rows moving — happens at once, as before, so nothing
 * flickers.
 */
class SlideUpItemAnimator(density: Float) : SimpleItemAnimator() {

    private companion object {
        const val DURATION_MS = 180L
    }

    private val distance = 40 * density
    private val pending = ArrayList<RecyclerView.ViewHolder>()
    private val running = ArrayList<RecyclerView.ViewHolder>()

    init {
        supportsChangeAnimations = false
    }

    override fun animateAdd(holder: RecyclerView.ViewHolder): Boolean {
        val vh = holder as? MessageAdapter.VH
        if (vh == null || !vh.slideIn) {
            dispatchAddFinished(holder)
            return false
        }
        vh.slideIn = false
        holder.itemView.animate().cancel()
        holder.itemView.translationY = distance
        holder.itemView.alpha = 0f
        pending.add(holder)
        return true
    }

    override fun runPendingAnimations() {
        val toRun = ArrayList(pending)
        pending.clear()
        for (holder in toRun) {
            running.add(holder)
            dispatchAddStarting(holder)
            holder.itemView.animate()
                .translationY(0f)
                .alpha(1f)
                .setDuration(DURATION_MS)
                .withEndAction { finish(holder) }
                .start()
        }
    }

    private fun finish(holder: RecyclerView.ViewHolder) {
        holder.itemView.translationY = 0f
        holder.itemView.alpha = 1f
        if (running.remove(holder) || pending.remove(holder)) dispatchAddFinished(holder)
        if (!isRunning) dispatchAnimationsFinished()
    }

    override fun animateRemove(holder: RecyclerView.ViewHolder): Boolean {
        dispatchRemoveFinished(holder)
        return false
    }

    override fun animateMove(holder: RecyclerView.ViewHolder, fromX: Int, fromY: Int, toX: Int, toY: Int): Boolean {
        dispatchMoveFinished(holder)
        return false
    }

    override fun animateChange(
        oldHolder: RecyclerView.ViewHolder, newHolder: RecyclerView.ViewHolder?,
        fromLeft: Int, fromTop: Int, toLeft: Int, toTop: Int
    ): Boolean {
        dispatchChangeFinished(oldHolder, true)
        if (newHolder != null && newHolder !== oldHolder) dispatchChangeFinished(newHolder, false)
        return false
    }

    override fun endAnimation(item: RecyclerView.ViewHolder) {
        item.itemView.animate().cancel()
        finish(item)
    }

    override fun endAnimations() {
        for (h in ArrayList(running + pending)) {
            h.itemView.animate().cancel()
            finish(h)
        }
    }

    override fun isRunning(): Boolean = pending.isNotEmpty() || running.isNotEmpty()
}
