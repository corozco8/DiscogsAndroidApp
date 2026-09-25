package com.example.discogsandroidapp

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class OrderItemDetailsCacheTest {
    private val saved = SavedOrderItemDetails(123, "2026-08-13", null, "Picture sleeve", 1_000_000)

    @Test fun missingDateAndCommentAreRestored() {
        val item = mergeSavedOrderItem(OrderItem(id = 123, posted = "", comments = " "), saved)
        assertEquals("2026-08-13", item.posted)
        assertEquals("Picture sleeve", item.comments)
    }
    @Test fun liveValuesTakePrecedence() {
        val item = mergeSavedOrderItem(OrderItem(id = 123, posted = "2026-09-01", comments = "Updated"), saved)
        assertEquals("2026-09-01", item.posted)
        assertEquals("Updated", item.comments)
    }
    @Test fun anotherListingNeverGetsTheseDetails() {
        val item = OrderItem(id = 456)
        assertEquals(item, mergeSavedOrderItem(item, saved))
    }
    @Test fun supportsStringListingIds() {
        assertEquals("Picture sleeve", mergeSavedOrderItem(OrderItem(id_string = "123"), saved).comments)
    }
    @Test fun storedDataRoundTrips() {
        assertEquals(saved, Json.decodeFromString<SavedOrderItemDetails>(Json.encodeToString(saved)))
    }
    @Test fun validCacheAvoidsRepeatedReadsIncludingBlankComments() {
        assertTrue(saved.copy(comments = "").isFresh(1_000_001))
        assertFalse(saved.isFresh(1_000_000 + 86_400_000L))
        assertFalse(saved.isFresh(999_999))
    }
    @Test fun missingDatesAreCheckedAgainSooner() {
        val missing = saved.copy(posted = null, dateAdded = null)
        assertTrue(missing.isFresh(1_000_001))
        assertFalse(missing.isFresh(1_300_000))
    }
}
