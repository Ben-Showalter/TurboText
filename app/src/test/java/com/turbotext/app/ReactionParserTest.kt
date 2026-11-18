package com.turbotext.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReactionParserTest {
    @Test fun iphoneLiked() {
        val p = ReactionParser.parse("Liked “See you at 5”")!!
        assertEquals(ReactionParser.Kind.ADD, p.kind)
        assertEquals("👍", p.emoji)
        assertEquals("See you at 5", p.quotedText)
    }

    @Test fun iphoneRemoved() = assertEquals(
        ReactionParser.Kind.REMOVE, ReactionParser.parse("Removed a heart from “Hi”")!!.kind
    )

    @Test fun googleMessages() = assertEquals("😂", ReactionParser.parse("😂 to “Hi”")!!.emoji)

    @Test fun image() = assertEquals(ReactionParser.Media.IMAGE, ReactionParser.parse("Loved an image")!!.media)

    @Test fun ordinaryText() = assertNull(ReactionParser.parse("Liked it a lot"))

    @Test fun loneEmoji() {
        assertEquals("👍", ReactionParser.loneEmoji(" 👍 "))
        assertEquals("❤️", ReactionParser.loneEmoji("❤️"))
        assertEquals("👍🏽", ReactionParser.loneEmoji("👍🏽"))
        assertNull(ReactionParser.loneEmoji("👍👍"))
        assertNull(ReactionParser.loneEmoji("👍 ok"))
        assertNull(ReactionParser.loneEmoji("ok"))
    }

    @Test fun composeAndRemove() {
        assertEquals("👍 to “Hi”", ReactionParser.compose("👍", "Hi"))
        assertEquals("Removed 👍 from “Hi”", ReactionParser.compose("👍", "Hi", remove = true))
        assertEquals("❤️ to an image", ReactionParser.compose("❤️", "", "image/jpeg"))
    }
}
