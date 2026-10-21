package com.turbotext.app

/** When a conversation reload should pull the list down to the newest
 *  message. Kept apart from ConversationActivity so it can be tested. */
object ScrollRules {
    /**
     * [wasEmpty]: nothing was showing yet (first open).
     * [wasAtBottom]: the newest message was fully in view before the reload.
     * [browsing]: a message is selected — the user is reading back.
     * [justSent]: this screen sent a message since the last reload.
     *
     * Reading back must never be interrupted: a reload from a status
     * change, a read flag or a message in another thread used to snap
     * the list to the bottom from wherever the user had scrolled to.
     */
    fun shouldStickToBottom(wasEmpty: Boolean, wasAtBottom: Boolean, browsing: Boolean, justSent: Boolean): Boolean =
        wasEmpty || justSent || (!browsing && wasAtBottom)
}
