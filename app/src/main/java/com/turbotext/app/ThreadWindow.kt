package com.turbotext.app

/**
 * How much of a conversation is shown (Settings > Advanced > Messages
 * Shown). A long thread used to load its whole history, and every new
 * message re-checked all of it, so sliding up got sluggish. Now only the
 * newest messages are shown, with Load More for older ones; nothing is
 * deleted.
 */
object ThreadWindow {
    /** The choices, in the order OK cycles through them; 0 means All. */
    val CHOICES = listOf(100, 200, 500, 0)
    const val DEFAULT = 200

    /** The newest [limit] of [messages] (oldest first), and whether there
     *  were more. A [limit] of 0 shows everything. */
    fun trim(messages: List<Message>, limit: Int): Pair<List<Message>, Boolean> =
        if (limit <= 0 || messages.size <= limit) messages to false
        else messages.takeLast(limit) to true

    /** The choice after [current] (Advanced settings' OK). */
    fun next(current: Int): Int = CHOICES[(CHOICES.indexOf(current) + 1).mod(CHOICES.size)]

    fun label(limit: Int): String = if (limit <= 0) "All" else limit.toString()
}
