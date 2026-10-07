package com.turbotext.app

import android.content.Context
import android.graphics.Rect
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * LinearLayoutManager for D-pad-driven lists.
 *
 * When focus moves to a row that's partly off screen, the stock manager
 * starts an animated scroll to reveal it. Holding UP/DOWN fires a new
 * focus change every key-repeat, each one interrupting the last
 * animation — that's the stutter, and the half-cut-off top row it used to
 * leave behind (which an on-idle "snap" then had to correct, a second
 * visible jump).
 *
 * Scrolling the focused row fully into view immediately instead keeps
 * every step aligned to whole rows, with no animation to interrupt.
 */
class RowSnapLayoutManager(context: Context) : LinearLayoutManager(context) {

    override fun requestChildRectangleOnScreen(
        parent: RecyclerView, child: View, rect: Rect, immediate: Boolean, focusedChildVisible: Boolean
    ): Boolean = super.requestChildRectangleOnScreen(parent, child, rect, true, focusedChildVisible)
}

/** Shared setup for the D-pad lists: fixed-size container, no change
 *  animations (each one is a cross-fade the slow GPU has to draw), and
 *  a few extra rows kept bound off-screen so a held key doesn't wait
 *  on a fresh bind for every new row. */
fun RecyclerView.setUpForDpad() {
    layoutManager = RowSnapLayoutManager(context)
    setHasFixedSize(true)
    itemAnimator = null
    setItemViewCacheSize(6)
}
