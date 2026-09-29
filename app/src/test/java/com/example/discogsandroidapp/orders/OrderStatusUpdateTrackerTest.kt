package com.example.discogsandroidapp.orders

import org.junit.Assert.*
import org.junit.Test

class OrderStatusUpdateTrackerTest {
    @Test fun busyStateIsImmediateAndDuplicateTapsCannotStartAnotherWrite() {
        val tracker = OrderStatusUpdateTracker()
        assertTrue(tracker.begin("1", "In Progress"))
        assertEquals("In Progress", tracker.states.value["1"]!!.settingStatus)
        assertFalse(tracker.begin("1", "In Progress"))
        assertFalse(tracker.begin("1", "Shipped"))
        assertTrue(tracker.begin("2", "Shipped"))
    }
    @Test fun completionClearsBusyWithoutChangingOtherOrders() {
        val tracker = OrderStatusUpdateTracker()
        tracker.begin("1", "In Progress")
        tracker.begin("2", "Shipped")
        tracker.finish("1")
        assertNull(tracker.states.value["1"])
        assertEquals("Shipped", tracker.states.value["2"]!!.settingStatus)
    }
    @Test fun failureReleasesBusyAndAllowsAnExplicitRetry() {
        val tracker = OrderStatusUpdateTracker()
        tracker.begin("1", "In Progress")
        tracker.finish("1", "Not confirmed")
        assertNull(tracker.states.value["1"]!!.settingStatus)
        assertEquals("Not confirmed", tracker.states.value["1"]!!.error)
        assertTrue(tracker.begin("1", "In Progress"))
        assertNull(tracker.states.value["1"]!!.error)
    }
}
