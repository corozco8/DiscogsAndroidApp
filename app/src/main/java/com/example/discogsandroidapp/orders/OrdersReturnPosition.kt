package com.example.discogsandroidapp.orders

import com.example.discogsandroidapp.data.DiscogsOrder

internal fun shouldReturnToPaymentReceivedOrders(
    previousStatus: String?, requestedStatus: String, confirmedStatus: String?, sourceFilter: String?
): Boolean = previousStatus.equals("Payment Received", true) &&
    requestedStatus.equals("In Progress", true) && confirmedStatus.equals("In Progress", true) &&
    sourceFilter.equals("Payment Received", true)

internal data class OrdersScrollPosition(val index: Int, val offset: Int)

internal fun restoredOrdersScrollPosition(
    orders: List<DiscogsOrder>, anchorId: String?, savedIndex: Int, savedOffset: Int
): OrdersScrollPosition {
    if (orders.isEmpty()) return OrdersScrollPosition(0, 0)
    val anchor = if (anchorId == null) -1 else orders.indexOfFirst { it.id == anchorId }
    val index = if (anchor >= 0) anchor else savedIndex.coerceIn(0, orders.lastIndex)
    return OrdersScrollPosition(index, savedOffset.coerceAtLeast(0))
}
