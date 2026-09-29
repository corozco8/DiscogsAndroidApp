package com.example.discogsandroidapp.orders

import com.example.discogsandroidapp.data.DiscogsOrder

import org.junit.Assert.*
import org.junit.Test

class OrdersReturnPositionTest {
    private val orders = List(20) { DiscogsOrder(id = it.toString(), status = "Payment Received") }

    @Test fun onlyConfirmedInProgressFromPaymentReceivedReturnsAutomatically() {
        assertTrue(shouldReturnToPaymentReceivedOrders("Payment Received", "In Progress", "In Progress", "Payment Received"))
        for (filter in listOf("All", "In Progress", "Shipped", null)) {
            assertFalse(shouldReturnToPaymentReceivedOrders("Payment Received", "In Progress", "In Progress", filter))
        }
        assertFalse(shouldReturnToPaymentReceivedOrders("Invoice Sent", "In Progress", "In Progress", "Payment Received"))
        assertFalse(shouldReturnToPaymentReceivedOrders("Payment Received", "Shipped", "Shipped", "Payment Received"))
        assertFalse(shouldReturnToPaymentReceivedOrders("Payment Received", "In Progress", "Payment Received", "Payment Received"))
    }

    @Test fun removingAnOrderAboveTheViewportPreservesTheVisibleOrderAndPixelOffset() {
        val remaining = orders.filterNot { it.id == "3" }
        assertEquals(OrdersScrollPosition(11, 27), restoredOrdersScrollPosition(remaining, "12", 12, 27))
    }

    @Test fun removingTheVisibleOrderKeepsTheNextOrderAtThatSameSpot() {
        val remaining = orders.filterNot { it.id == "12" }
        val target = restoredOrdersScrollPosition(remaining, "12", 12, 27)
        assertEquals(OrdersScrollPosition(12, 27), target)
        assertEquals("13", remaining[target.index].id)
    }

    @Test fun removingAnOrderBelowTheViewportDoesNotMoveTheViewport() {
        assertEquals(OrdersScrollPosition(12, 27), restoredOrdersScrollPosition(orders.filterNot { it.id == "18" }, "12", 12, 27))
    }

    @Test fun removedLastOrderAndEmptyListsRestoreSafely() {
        assertEquals(OrdersScrollPosition(18, 27), restoredOrdersScrollPosition(orders.dropLast(1), "19", 19, 27))
        assertEquals(OrdersScrollPosition(0, 0), restoredOrdersScrollPosition(emptyList(), "19", 19, 27))
    }
}
