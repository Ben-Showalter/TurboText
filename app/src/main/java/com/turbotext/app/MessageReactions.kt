package com.turbotext.app

/*
 * Adapted from DPAD Messaging's MessageReactions
 * (https://github.com/jbriones95/DPAD-Messaging), MIT License,
 * Copyright (c) its authors. Changes for TurboText: works on TurboText's
 * Message, lone-emoji reactions, and returns the finished list.
 */

/**
 * Turns reaction texts ("Liked “…”", "👍 to “…”", or a lone "👍" sent
 * by itself) into reactions on the message they refer to, and "Edited to
 * “…”" into the edited text.
 *
 * Display only: nothing in the phone's message store changes, so the
 * Show Reactions setting can bring every original text back. A reaction
 * text is only hidden when the message it points at is found.
 */
object MessageReactions {

    /** SMS and MMS ids come from different tables. */
    private data class Key(val id: Long, val isMms: Boolean)

    private fun keyOf(m: Message) = Key(m.id, m.isMms)

    /**
     * [messages] oldest first. Returns them with reaction texts left out,
     * reactions put on their targets, and edits applied. A reaction of
     * mine that is scheduled or failed stays a message, so it can be seen.
     */
    fun apply(messages: List<Message>): List<Message> {
        val hidden = HashSet<Key>()
        val edits = HashMap<Key, String>()
        // target -> (sender -> reaction): one reaction per person per message.
        val byTarget = HashMap<Key, LinkedHashMap<String, Reaction>>()

        for ((index, message) in messages.withIndex()) {
            if (message.body.isBlank() || hasAttachments(message)) continue
            // A reaction of mine that failed or is scheduled stays visible as a message.
            if (message.isOutgoing && message.sendStatus in setOf("scheduled", "failed")) continue

            val parsed = ReactionParser.parse(message.body)
            if (parsed == null) {
                // A lone emoji from the other person reacts to the message just before it.
                if (message.isOutgoing) continue
                val emoji = ReactionParser.loneEmoji(message.body) ?: continue
                val target = (index - 1 downTo 0).map { messages[it] }.firstOrNull { keyOf(it) !in hidden }
                    ?: continue
                react(byTarget, target, message, emoji, add = true)
                hidden.add(keyOf(message))
                continue
            }

            if (parsed.kind == ReactionParser.Kind.EDIT) {
                if (message.isOutgoing) continue
                val target = findTarget(messages, index, parsed, hidden, edits, editOf = message) ?: continue
                edits[keyOf(target)] = parsed.quotedText
                hidden.add(keyOf(message))
                continue
            }

            val target = findTarget(messages, index, parsed, hidden, edits, editOf = null) ?: continue
            react(byTarget, target, message, parsed.emoji, add = parsed.kind == ReactionParser.Kind.ADD)
            hidden.add(keyOf(message))
        }

        if (hidden.isEmpty() && edits.isEmpty()) return messages
        return messages.mapNotNull { m ->
            val key = keyOf(m)
            if (key in hidden) return@mapNotNull null
            val reactions = byTarget[key]?.values?.toList().orEmpty()
            val edit = edits[key]
            if (reactions.isEmpty() && edit == null) m
            else m.copy(
                reactions = reactions,
                body = edit ?: m.body,
                edited = edit != null || m.edited
            )
        }
    }

    private fun react(
        byTarget: HashMap<Key, LinkedHashMap<String, Reaction>>,
        target: Message, from: Message, emoji: String, add: Boolean
    ) {
        val senders = byTarget.getOrPut(keyOf(target)) { LinkedHashMap() }
        val sender = senderKey(from)
        senders.remove(sender)
        if (add) {
            senders[sender] = Reaction(
                emoji = emoji,
                who = if (from.isOutgoing) "You" else (from.senderName ?: from.address),
                isMine = from.isOutgoing,
                date = from.date
            )
        }
    }

    /** "Edited to “new text”" names only the new text, so it edits the
     *  sender's own newest earlier message. Reactions quote the target. */
    private fun findTarget(
        messages: List<Message>,
        index: Int,
        parsed: ReactionParser.Parsed,
        hidden: Set<Key>,
        edits: Map<Key, String>,
        editOf: Message?
    ): Message? {
        val quote = normalize(if (parsed.isTruncated) parsed.quotePrefix else parsed.quotedText)
        for (i in index - 1 downTo 0) {
            val candidate = messages[i]
            val key = keyOf(candidate)
            if (key in hidden) continue
            if (editOf != null) {
                if (!candidate.isOutgoing && senderKey(candidate) == senderKey(editOf) &&
                    candidate.body.isNotBlank() && !hasAttachments(candidate)
                ) return candidate
                continue
            }
            val matches = if (parsed.media != ReactionParser.Media.NONE) {
                matchesMedia(candidate, parsed.media)
            } else {
                val body = normalize(edits[key] ?: candidate.body)
                when {
                    quote.isEmpty() || body.isEmpty() -> false
                    parsed.isTruncated -> body.startsWith(quote)
                    else -> body == quote
                }
            }
            if (matches) return candidate
        }
        return null
    }

    private fun matchesMedia(m: Message, media: ReactionParser.Media): Boolean = when (media) {
        ReactionParser.Media.IMAGE -> m.imageUri != null
        ReactionParser.Media.VIDEO -> m.videoUri != null
        ReactionParser.Media.AUDIO -> m.audioUri != null
        ReactionParser.Media.ANY -> hasAttachments(m)
        ReactionParser.Media.NONE -> false
    }

    internal fun hasAttachments(m: Message): Boolean =
        m.imageUri != null || m.videoUri != null || m.audioUri != null || m.fileUri != null || m.vcardUri != null

    private fun senderKey(m: Message): String {
        if (m.isOutgoing) return "me"
        val digits = m.address.filter(Char::isDigit)
        return if (digits.length >= 7) digits.takeLast(10) else m.address.trim().lowercase()
    }

    private val whitespace = Regex("\\s+")

    /** Quote matching ignores smart punctuation and spacing differences. */
    private fun normalize(text: String): String = text
        .replace('’', '\'').replace('‘', '\'')
        .replace('“', '"').replace('”', '"')
        .replace(' ', ' ')
        .replace(whitespace, " ")
        .trim()

    /** What to send for a reaction to [target] by me: the emoji, or its
     *  removal when [emoji] is already my reaction (reactions toggle). */
    fun composeFor(target: Message, emoji: String): String {
        val mine = target.reactions.firstOrNull { it.isMine }
        val mime = when {
            target.imageUri != null -> "image/"
            target.videoUri != null -> "video/"
            target.audioUri != null -> "audio/"
            else -> ""
        }
        return ReactionParser.compose(emoji, target.body, mime, remove = mine?.emoji == emoji)
    }
}
