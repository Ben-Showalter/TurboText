package com.turbotext.app

import com.turbotext.app.ConversationWallpaper.Choice
import org.junit.Assert.assertEquals
import org.junit.Test

class ConversationWallpaperChoiceTest {
    @Test fun defaultIsRandom() = assertEquals(Choice.Random, ConversationWallpaper.parseChoice(null))

    @Test fun roundTrips() {
        for (c in listOf(Choice.Random, Choice.None, Choice.Custom, Choice.Pack("wallpaper_pack_07.jpg"))) {
            assertEquals(c, ConversationWallpaper.parseChoice(ConversationWallpaper.encode(c)))
        }
    }

    @Test fun unknownIsRandom() = assertEquals(Choice.Random, ConversationWallpaper.parseChoice("contact"))

    @Test fun labels() {
        assertEquals("Picture 12", ConversationWallpaper.label(Choice.Pack("wallpaper_pack_12.jpg")))
        assertEquals("Gallery Picture", ConversationWallpaper.label(Choice.Custom))
        assertEquals("None", ConversationWallpaper.label(Choice.None))
    }
}
