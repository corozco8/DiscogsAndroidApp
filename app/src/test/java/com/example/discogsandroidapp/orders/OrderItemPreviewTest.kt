package com.example.discogsandroidapp.orders

import com.example.discogsandroidapp.data.OrderItem
import com.example.discogsandroidapp.data.DiscogsOrder
import com.example.discogsandroidapp.data.OrderPrice
import com.example.discogsandroidapp.data.OrderReleaseInfo
import org.junit.Assert.*
import org.junit.Test

class OrderItemPreviewTest {
    @Test fun anOpenPreviewReceivesNewDatesForTheCorrectCopyOfTheRelease() {
        val selected = OrderItem(id = 2, release = OrderReleaseInfo(id = 6))
        val order = DiscogsOrder(items = listOf(selected.copy(id = 1, posted = "2024-01-01"),
            selected.copy(posted = "2024-02-02")))
        assertEquals("2024-02-02", currentOrderPreviewItem(order, selected).posted)
        assertNull(selected.posted)
    }
    @Test fun previewPreservesListingDateCommentsConditionsAndPaidPrice() {
        val item = OrderItem(id = 42, release = OrderReleaseInfo(id = 5, description = "Fleetwood Mac - Rumours"),
            posted = "2024-01-02T10:00:00", date_added = "2024-01-01", comments = "Original sleeve",
            condition = "Very Good (VG)", media_condition = "Very Good Plus (VG+)",
            sleeve_condition = "Near Mint (NM or M-)", price = OrderPrice(19.99, "USD"))
        val preview = item.previewListing()
        assertEquals(item.posted, preview.posted)
        assertEquals(item.date_added, preview.dateAdded)
        assertEquals(item.comments, preview.comments)
        assertEquals(item.media_condition, preview.condition)
        assertEquals(item.sleeve_condition, preview.sleeve_condition)
        assertEquals(19.99, preview.price!!.value, 0.001)
        assertEquals(5, preview.release.id)
        assertEquals("Sold", preview.status)
        assertEquals("2024-01-02T10:00:00", item.posted)
    }
    @Test fun missingListingFieldsUseOrderFallbacks() {
        val preview = OrderItem(id_string = "45", condition = "Good (G)",
            release = OrderReleaseInfo(id = 6, title = "Rumours")).previewListing()
        assertEquals(45, preview.id)
        assertEquals("Good (G)", preview.condition)
        assertEquals("Rumours", preview.release.description)
        assertNull(preview.posted)
        assertNull(preview.price)
    }
}
