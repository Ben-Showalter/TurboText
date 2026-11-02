package com.turbotext.app

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaItemsTest {

    private fun item(name: String, date: Long, video: Boolean = false, album: String? = null) =
        MediaItem("content://media/$name", if (video) "video/mp4" else "image/jpeg", date, video, album = album)

    @Test
    fun picturesAndVideosNewestFirst() {
        val merged = MediaItems.merge(
            listOf(item("p1", 300), item("p2", 100)),
            listOf(item("v1", 200, video = true), item("v2", 400, video = true))
        )
        assertEquals(listOf("v2", "p1", "v1", "p2"), merged.map { it.uri.substringAfterLast('/') })
    }

    @Test
    fun videoLengthLabels() {
        assertEquals("0:12", MediaItems.durationLabel(12_000))
        assertEquals("3:05", MediaItems.durationLabel(185_000))
        assertEquals("1:02:09", MediaItems.durationLabel(3_729_000))
        assertEquals("0:00", MediaItems.durationLabel(0))
    }

    @Test
    fun albumsBiggestFirst() {
        val items = listOf(item("a", 1, album = "Camera"), item("b", 2, album = "Camera"), item("c", 3, album = "Screenshots"), item("d", 4))
        assertEquals(listOf("Camera", "Screenshots"), MediaItems.albums(items))
    }
}
