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

    @Test
    fun onlyNotificationSoundsCountAsPhoneSounds() {
        // A ringtone this phone also flags as a notification: out.
        assertEquals(false, NotificationSounds.isNotificationSound("/system/media/audio/ringtones/Ring.ogg", true, true, false))
        assertEquals(false, NotificationSounds.isNotificationSound("/system/media/audio/alarms/Wake.ogg", true, false, true))
        assertEquals(true, NotificationSounds.isNotificationSound("/system/media/audio/notifications/Ding.ogg", true, true, false))
        assertEquals(true, NotificationSounds.isNotificationSound("/sdcard/Sounds/beep.mp3", true, false, false))
        assertEquals(false, NotificationSounds.isNotificationSound("/sdcard/Music/song.mp3", false, false, false))
    }
}
