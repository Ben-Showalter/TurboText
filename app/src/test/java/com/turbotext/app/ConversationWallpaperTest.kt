package com.turbotext.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ConversationWallpaperTest {
    private val pack = (1..36).map { "wallpaper_pack_%02d.jpg".format(it) }

    @Test
    fun keepsTheStoredWallpaper() {
        assertEquals("wallpaper_pack_07.jpg", ConversationWallpaper.pick("wallpaper_pack_07.jpg", pack, Random(1)))
    }

    @Test
    fun picksFromThePackWhenNoneOrGone() {
        val first = ConversationWallpaper.pick(null, pack, Random(42))
        assertTrue(first in pack)
        // A stored file no longer in the pack gets a new one from it.
        assertTrue(ConversationWallpaper.pick("old_removed.jpg", pack, Random(7)) in pack)
        assertNull(ConversationWallpaper.pick(null, emptyList()))
    }
}
