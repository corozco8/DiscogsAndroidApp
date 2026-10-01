package com.example.discogsandroidapp.inventory

import com.example.discogsandroidapp.data.LocalInventoryListingEntity
import org.junit.Assert.*
import org.junit.Test

class StoreSearchIndexTest {
    private fun listing(id: Long, artist: String = "Fleetwood Mac", title: String = "Rumours",
        status: String = "For Sale", price: Double = id.toDouble(), comments: String = "BSK 3010") =
        LocalInventoryListingEntity(id, id + 1_000, artist, title, "", status, "Very Good Plus (VG+)",
            "Near Mint (NM or M-)", comments, price, "USD", "2024-01-02", id, true, id, id)

    @Test fun searchesAllThreeThousandRecordsIncludingTheOldestListing() {
        val rows = (1L..3_000L).map { listing(it, "Artist $it", "Album $it") }.toMutableList()
        rows[0] = listing(1)
        val index = StoreSearchIndex(rows)
        assertEquals(3_000, index.size)
        assertEquals(listOf(1L), index.search(StoreQuery("  FLEETWOOD   rumours ")).map { it.id })
    }
    @Test fun blankSearchRestoresInventoryAndInactiveRecordsAreExcluded() {
        val index = StoreSearchIndex(listOf(listing(1), listing(2, status = "for sale"),
            listing(3, status = "Sold"), listing(4, status = "Draft")))
        assertEquals(2, index.size)
        assertEquals(listOf(2L, 1L), index.search(StoreQuery(" ")).map { it.id })
        assertTrue(index.search(StoreQuery("missing album")).isEmpty())
    }
    @Test fun commentsConditionsAndIdsAreSearchable() {
        val index = StoreSearchIndex(listOf(listing(123)))
        listOf("BSK 3010", "VG+", "1123", "123").forEach {
            assertEquals(listOf(123L), index.search(StoreQuery(it)).map { row -> row.id })
        }
    }
    @Test fun filteringPreservesTheChosenSortAndReversesItCorrectly() {
        val index = StoreSearchIndex(listOf(listing(1, title = "Tusk", price = 5.0),
            listing(2, title = "Rumours", price = 20.0), listing(3, artist = "Other artist", title = "Alice")))
        assertEquals(listOf(2L, 1L), index.search(StoreQuery("Fleetwood", "title", "asc")).map { it.id })
        assertEquals(listOf(1L, 2L), index.search(StoreQuery("Fleetwood", "title", "desc")).map { it.id })
        assertEquals(listOf(1L, 2L), index.search(StoreQuery("Fleetwood", "price", "asc")).map { it.id })
        assertEquals(listOf(2L, 1L), index.search(StoreQuery("Fleetwood", "price", "desc")).map { it.id })
    }
    @Test fun cachedSearchRetainsTheListingDetailsNeededForViewingAndEditing() {
        val row = listing(123, price = 19.5)
        val result = StoreSearchIndex(listOf(row)).search(StoreQuery("Rumours")).single()
        assertEquals("Fleetwood Mac - Rumours", result.release.description)
        assertEquals("2024-01-02", result.posted)
        assertEquals("Very Good Plus (VG+)", result.condition)
        assertEquals(row.comments, result.comments)
        assertEquals(19.5, result.price!!.value, 0.0)
    }
}
