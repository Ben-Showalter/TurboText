package com.turbotext.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SendStateTest {
    @Test fun smsSendingBecomesEither() {
        assertTrue(SendState.smsMayBecome(SendState.OUTBOX, SendState.SENT, 1))
        assertTrue(SendState.smsMayBecome(SendState.OUTBOX, SendState.FAILED, 1))
    }

    @Test fun smsSentNeverBecomesFailed() =
        assertFalse(SendState.smsMayBecome(SendState.SENT, SendState.FAILED, 1))

    @Test fun smsFailedPartStaysFailed() =
        assertFalse(SendState.smsMayBecome(SendState.FAILED, SendState.SENT, 3))

    @Test fun smsGivenUpOnePartThatWentOutIsSent() =
        assertTrue(SendState.smsMayBecome(SendState.FAILED, SendState.SENT, 1))

    @Test fun mmsLateFailureDoesNotUndoSent() =
        assertFalse(SendState.mmsMayBecome(SendState.SENT, SendState.FAILED))

    @Test fun mmsLateSuccessAfterGiveUpIsSent() =
        assertTrue(SendState.mmsMayBecome(SendState.FAILED, SendState.SENT))
}
