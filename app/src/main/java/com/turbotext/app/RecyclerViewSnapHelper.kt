package com.turbotext.app

import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/** A real screen recording showed the D-pad-driven scroll (rapid
 *  key-repeat while holding UP/DOWN) settling at a scroll offset that
 *  isn't aligned to a row boundary — leaving whatever row landed at the
 *  top partially clipped by the header above the list, at whatever
 *  position that happened to be, not just position 0. Likely cause:
 *  each focus change fires its own scroll-into-view request, and rapid
 *  repeated key events can interrupt one request's animation with the
 *  next before it finishes, leaving a leftover partial offset once the
 *  key presses stop. Rather than chase the exact interruption, this is
 *  a general safety net: whenever the list settles (goes idle), check
 *  whether the top-most visible row is partially cut off, and if so,
 *  nudge it fully into view. */
fun RecyclerView.snapTopRowOnIdle() {
    addOnScrollListener(object : RecyclerView.OnScrollListener() {
        override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
            if (newState != RecyclerView.SCROLL_STATE_IDLE) return
            val lm = recyclerView.layoutManager as? LinearLayoutManager ?: return
            val firstPos = lm.findFirstVisibleItemPosition()
            if (firstPos == RecyclerView.NO_POSITION) return
            val firstView = lm.findViewByPosition(firstPos) ?: return
            // top < 0 means part of this row is scrolled off above the
            // RecyclerView's own visible area — nudge it back down just
            // enough to fully reveal it, rather than leaving it sliced.
            if (firstView.top < 0) {
                recyclerView.scrollBy(0, firstView.top)
            }
        }
    })
}
