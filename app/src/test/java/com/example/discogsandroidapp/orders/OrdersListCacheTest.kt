package com.example.discogsandroidapp.orders

import com.example.discogsandroidapp.data.DiscogsOrder

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class OrdersListCacheTest {
    private val paid = DiscogsOrder(id = "order-1", status = "Payment Received", created = "2026-09-01")
    private val key = ordersCacheKey("example-account-token", "Payment Received", "asc")
    private fun snapshot(orders: List<DiscogsOrder> = listOf(paid), cacheKey: OrdersCacheKey = key) =
        SavedOrdersList(cacheKey, orders, page = 2, pages = 3, total = 201)

    @Test fun reopeningUsesTheSavedRowsDuringRefreshAndAfterFailure() {
        val cache = OrdersMemoryCache()
        val saved = snapshot()
        cache.put(saved)
        val loading = cache.get(key)!!.screen(refreshing = true)
        assertEquals(listOf(paid), loading.orders)
        assertTrue(loading.isRefreshing)
        val failed = cache.get(key)!!.screen(error = "Network unavailable")
        assertEquals(loading.orders, failed.orders)
        assertFalse(failed.isRefreshing)
        assertEquals("Network unavailable", failed.refreshError)
        assertTrue(failed.hasMore)
    }

    @Test fun successfulRefreshReplacesDisappearedOrdersIncludingAnEmptyList() {
        val cache = OrdersMemoryCache()
        cache.put(snapshot())
        cache.put(snapshot(emptyList()).copy(page = 1, pages = 1, total = 0))
        assertTrue(cache.get(key)!!.orders.isEmpty())
        assertFalse(cache.get(key)!!.screen().hasMore)
    }

    @Test fun isolatesAccountsFiltersAndSortsWithoutStoringTheToken() {
        val cache = OrdersMemoryCache()
        cache.put(snapshot())
        assertNull(cache.get(ordersCacheKey("other-account", "Payment Received", "asc")))
        assertNull(cache.get(ordersCacheKey("example-account-token", "In Progress", "asc")))
        assertNull(cache.get(ordersCacheKey("example-account-token", "Payment Received", "desc")))
        assertEquals(ordersCacheKey("t", "All", "desc"), ordersCacheKey("t", "All Orders", "desc"))
        assertFalse(Json.encodeToString(snapshot()).contains("example-account-token"))
    }

    @Test fun snapshotsRoundTripForAppRestarts() {
        val restored = Json.decodeFromString<List<SavedOrdersList>>(Json.encodeToString(listOf(snapshot())))
        val cache = OrdersMemoryCache()
        cache.restore(restored)
        assertEquals(snapshot(), cache.get(key))
    }

    @Test fun aLateDiskRestoreCannotOverwriteNewerMemoryData() {
        val cache = OrdersMemoryCache()
        val updated = snapshot(listOf(paid.copy(status = "In Progress")))
        cache.put(updated)
        cache.restore(listOf(snapshot()))
        assertEquals(updated, cache.get(key))
    }

    @Test fun verifiedStatusMovesTheOrderBetweenCachedFilters() {
        val cache = OrdersMemoryCache()
        val progressKey = ordersCacheKey("example-account-token", "In Progress", "desc")
        val allKey = ordersCacheKey("example-account-token", "All", "desc")
        val otherKey = ordersCacheKey("different-account", "Payment Received", "asc")
        cache.put(snapshot())
        cache.put(snapshot(emptyList(), progressKey).copy(total = 0, page = 1, pages = 1))
        cache.put(snapshot(cacheKey = allKey))
        cache.put(snapshot(cacheKey = otherKey))
        val updated = paid.copy(status = "In Progress")
        cache.updateOrder(key.account, updated)
        assertTrue(cache.get(key)!!.orders.isEmpty())
        assertEquals(200, cache.get(key)!!.total)
        assertEquals(1, cache.get(key)!!.page) // overlap shifted page boundary
        assertEquals(listOf(updated), cache.get(progressKey)!!.orders)
        assertEquals(1, cache.get(progressKey)!!.total)
        assertEquals(listOf(updated), cache.get(allKey)!!.orders)
        assertEquals(201, cache.get(allKey)!!.total)
        assertEquals(listOf(paid), cache.get(otherKey)!!.orders)
    }

    @Test fun repeatedStatusConfirmationDoesNotDoubleCountOrders() {
        val updated = paid.copy(status = "In Progress")
        val saved = snapshot(cacheKey = ordersCacheKey("example-account-token", "All", "asc"))
        assertEquals(saved.withUpdatedOrder(updated), saved.withUpdatedOrder(updated).withUpdatedOrder(updated))
    }

    @Test fun overlappingPagesUpdateExistingRowsWithoutDuplicates() {
        val newer = paid.copy(lastActivity = "2026-09-02")
        val second = paid.copy(id = "order-2")
        val result = mergeOrdersPage(listOf(paid), listOf(newer, second, second,
            paid.copy(id = "shipped", status = "Shipped")), "Payment Received")
        assertEquals(listOf(newer, second), result)
    }

    @Test fun cacheIsBounded() {
        val cache = OrdersMemoryCache()
        repeat(17) { index -> cache.put(snapshot(cacheKey = ordersCacheKey("account-$index", "All", "asc"))) }
        assertEquals(16, cache.savedEntries().size)
        assertNull(cache.get(ordersCacheKey("account-0", "All", "asc")))
    }

    @Test fun partialAllOrdersCacheDoesNotDoubleCountAnOrderFromAnotherPage() {
        val saved = snapshot(emptyList(), ordersCacheKey("example-account-token", "All", "desc"))
        val updated = saved.withUpdatedOrder(paid.copy(status = "In Progress"))
        assertEquals(saved.total, updated.total)
        assertEquals(saved.page, updated.page)
    }

    @Test fun veryLargeListsKeepABoundedDiskCacheWithoutSkippingPages() {
        val saved = snapshot(List(1_100) { paid.copy(id = it.toString()) }).copy(page = 11, pages = 12, total = 1_200)
        val bounded = saved.forDisk()
        assertEquals(1_000, bounded.orders.size)
        assertEquals(0, bounded.page)
        assertEquals(1_200, bounded.total)
        assertEquals(1_100, saved.orders.size)
    }

    @Test fun oldDiskEntriesCannotEvictAFreshMemorySnapshot() {
        val cache = OrdersMemoryCache()
        cache.put(snapshot())
        cache.restore(List(16) { snapshot(cacheKey = ordersCacheKey("old-$it", "All", "asc")) })
        assertEquals(snapshot(), cache.get(key))
        assertEquals(16, cache.savedEntries().size)
    }
}
