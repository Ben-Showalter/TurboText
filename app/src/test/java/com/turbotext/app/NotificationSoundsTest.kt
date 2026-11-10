package com.turbotext.app

import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationSoundsTest {
    @Test
    fun builtInFileNamesReadNicely() {
        assertEquals("F1 New SMS", NotificationSounds.displayName("F1_New_SMS.ogg"))
        assertEquals("Arcturus 2", NotificationSounds.displayName("Arcturus 2.ogg"))
        assertEquals("Tethys", NotificationSounds.displayName("Tethys"))
    }
}
