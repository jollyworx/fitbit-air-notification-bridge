package com.fitbit.goldengatehost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AirNotificationFilterTest {
    private fun classify(
        ongoing: Boolean = false,
        callCategory: Boolean = false,
        callType: Int = 0,
        fullScreen: Boolean = false,
        chronometer: Boolean = false,
        summary: Boolean = false
    ) = AirNotificationFilter.classify(summary, ongoing, callCategory, callType, fullScreen, chronometer)

    @Test
    fun regularMessagesAndEmailsStillPassButBackgroundWorkDoesNot() {
        assertEquals(AirNotificationFilter.Delivery.NORMAL, classify())
        assertEquals(AirNotificationFilter.Delivery.IGNORE, classify(ongoing = true))
        assertEquals(AirNotificationFilter.Delivery.IGNORE, classify(summary = true))
        assertEquals(AirNotificationFilter.Delivery.NORMAL, classify(fullScreen = true))
    }

    @Test
    fun modernIncomingCallsPassDespiteTheOngoingFlag() {
        assertEquals(
            AirNotificationFilter.Delivery.INCOMING_CALL,
            classify(ongoing = true, callCategory = true, callType = 1)
        )
        // The explicit call type also works if an app omits CATEGORY_CALL.
        assertEquals(AirNotificationFilter.Delivery.INCOMING_CALL, classify(callType = 1))
        assertEquals(AirNotificationFilter.Delivery.IGNORE, classify(callType = 1, summary = true))
    }

    @Test
    fun answeredOrScreenedCallsNeverStartAnotherAlert() {
        assertEquals(
            AirNotificationFilter.Delivery.IGNORE,
            classify(ongoing = true, callCategory = true, callType = 2, fullScreen = true)
        )
        assertEquals(AirNotificationFilter.Delivery.IGNORE, classify(callCategory = true, callType = 3))
    }

    @Test
    fun legacyRingingCallsPassButElapsedCallUpdatesAndMissedCallsAreDistinct() {
        assertEquals(
            AirNotificationFilter.Delivery.INCOMING_CALL,
            classify(ongoing = true, callCategory = true, fullScreen = true)
        )
        assertEquals(
            AirNotificationFilter.Delivery.IGNORE,
            classify(ongoing = true, callCategory = true, fullScreen = true, chronometer = true)
        )
        assertEquals(AirNotificationFilter.Delivery.IGNORE, classify(ongoing = true, callCategory = true))
        assertEquals(AirNotificationFilter.Delivery.NORMAL, classify(callCategory = true))
    }

    @Test
    fun repeatedRingingUpdatesAlertOnceAndANewCallCanReuseTheNotificationKey() {
        val tracker = AirIncomingCallTracker()
        val key = "dialer:incoming-call"
        assertFalse(tracker.wasDelivered(key))
        tracker.markDelivered(key)
        assertTrue(tracker.wasDelivered(key))
        assertFalse(tracker.wasDelivered("whatsapp:incoming-call"))
        tracker.onRemoved(key)
        assertFalse(tracker.wasDelivered(key))
    }

    @Test
    fun abandonedCallKeysCannotGrowWithoutBound() {
        val tracker = AirIncomingCallTracker(maxKeys = 2)
        tracker.markDelivered("one")
        tracker.markDelivered("two")
        tracker.markDelivered("three")
        assertFalse(tracker.wasDelivered("one"))
        assertTrue(tracker.wasDelivered("two"))
        assertTrue(tracker.wasDelivered("three"))
    }
}
