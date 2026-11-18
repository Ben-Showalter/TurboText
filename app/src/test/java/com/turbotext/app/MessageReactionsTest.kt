package com.turbotext.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageReactionsTest {
    private fun msg(id: Long, body: String, out: Boolean, image: String? = null) =
        Message(id = id, address = "5551234567", body = body, date = id * 1000, isOutgoing = out, imageUri = image)

    @Test fun iphoneReactionGoesOnTarget() {
        val out = MessageReactions.apply(listOf(msg(1, "See you at 5", true), msg(2, "Liked “See you at 5”", false)))
        assertEquals(1, out.size)
        assertEquals("👍", out[0].reactions.single().emoji)
    }

    @Test fun ordinaryTextUntouched() {
        val list = listOf(msg(1, "Hi", true), msg(2, "Liked “Something else”", false))
        assertEquals(list, MessageReactions.apply(list))
    }

    @Test fun loneIncomingEmojiReactsToPrevious() {
        val out = MessageReactions.apply(listOf(msg(1, "Dinner at 6?", true), msg(2, "👍", false)))
        assertEquals(1, out.size)
        assertEquals("👍", out[0].reactions.single().emoji)
    }

    @Test fun loneOutgoingEmojiStaysAMessage() {
        val list = listOf(msg(1, "Dinner at 6?", false), msg(2, "👍", true))
        assertEquals(2, MessageReactions.apply(list).size)
    }

    @Test fun twoEmojiStayAMessage() {
        val list = listOf(msg(1, "Dinner at 6?", true), msg(2, "👍👍", false))
        assertEquals(2, MessageReactions.apply(list).size)
    }

    @Test fun newReactionReplacesAndRemoveClears() {
        val out = MessageReactions.apply(
            listOf(msg(1, "Hi", true), msg(2, "👍 to “Hi”", false), msg(3, "❤️ to “Hi”", false))
        )
        assertEquals(listOf("❤️"), out.single().reactions.map { it.emoji })
        val removed = MessageReactions.apply(
            listOf(msg(1, "Hi", true), msg(2, "👍 to “Hi”", false), msg(3, "Removed 👍 from “Hi”", false))
        )
        assertTrue(removed.single().reactions.isEmpty())
    }

    @Test fun imageReaction() {
        val out = MessageReactions.apply(listOf(msg(1, "", true, image = "content://p/1"), msg(2, "Loved an image", false)))
        assertEquals("❤️", out.single().reactions.single().emoji)
    }

    @Test fun toggleSendsRemoveForMyOwnEmoji() {
        val target = msg(1, "Hi", false).copy(reactions = listOf(Reaction("👍", "You", true, 0)))
        assertEquals("Removed 👍 from “Hi”", MessageReactions.composeFor(target, "👍"))
        assertEquals("❤️ to “Hi”", MessageReactions.composeFor(target, "❤️"))
    }
}
