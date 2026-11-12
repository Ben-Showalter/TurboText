package com.turbotext.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NotificationTextTest {
    @Test fun firstMessageHasNoCount() = assertNull(NotificationHelper.otherMessages(0))
    @Test fun oneOther() = assertEquals("+1 other message", NotificationHelper.otherMessages(1))
    @Test fun severalOthers() = assertEquals("+3 other messages", NotificationHelper.otherMessages(3))
}
