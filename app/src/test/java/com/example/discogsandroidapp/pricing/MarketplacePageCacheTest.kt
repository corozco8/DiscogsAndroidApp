package com.example.discogsandroidapp.pricing

import org.junit.Assert.*
import org.junit.Test

class MarketplacePageCacheTest {
    @Test fun pageIsReusedOnlyBeforeItsFixedExpiry() {
        var now = 0L
        val destroyed = mutableListOf<String>()
        val pages = RecentMarketplacePages<String>({ now }, { destroyed.add(it) })
        pages.put(1, "first", 900_000)
        now = 899_999
        assertEquals("first", pages.take(1))
        assertNull(pages.take(1))
        pages.put(1, "first", 1)
        now = 900_000
        assertNull(pages.take(1))
        assertEquals(listOf("first"), destroyed)
    }
    @Test fun aThirdPageEvictsTheOldestAndReplacementDestroysPreviousPage() {
        val destroyed = mutableListOf<String>()
        val pages = RecentMarketplacePages<String>({ 0L }, { destroyed.add(it) })
        pages.put(1, "first", 100)
        pages.put(2, "second", 100)
        pages.put(3, "third", 100)
        assertEquals(listOf("first"), destroyed)
        pages.put(2, "second replacement", 100)
        assertEquals(listOf("first", "second"), destroyed)
        pages.clear()
        assertEquals(4, destroyed.size)
    }
    @Test fun expiredAndZeroLifetimePagesAreDestroyedWithoutReturningThem() {
        var now = 0L
        val destroyed = mutableListOf<String>()
        val pages = RecentMarketplacePages<String>({ now }, { destroyed.add(it) })
        pages.put(1, "expired", 10)
        now = 10
        pages.evictExpired()
        pages.put(2, "already old", 0)
        assertNull(pages.take(1))
        assertNull(pages.take(2))
        assertEquals(listOf("expired", "already old"), destroyed)
    }
}
