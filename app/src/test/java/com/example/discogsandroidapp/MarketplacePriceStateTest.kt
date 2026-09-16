package com.example.discogsandroidapp

import org.junit.Assert.*
import org.junit.Test

class MarketplacePriceStateTest {
    @Test fun canonicalListingRedirectsStayWithinTheRequestedReleaseAndPage() {
        assertTrue(isMarketplacePriceUrlAllowed("https", "discogs.com", "/sell/list/", "123", null, 123, 1))
        assertTrue(isMarketplacePriceUrlAllowed("https", "www.discogs.com", "/sell/release/123", null, "2", 123, 2))
        assertFalse(isMarketplacePriceUrlAllowed("https", "www.discogs.com", "/sell/list", "123", null, 123, 2))
        assertFalse(isMarketplacePriceUrlAllowed("https", "www.discogs.com", "/sell/release/456", null, "1", 123, 1))
        assertFalse(isMarketplacePriceUrlAllowed("https", "example.com", "/sell/list", "123", "1", 123, 1))
        assertFalse(isMarketplacePriceUrlAllowed("http", "www.discogs.com", "/sell/list", "123", "1", 123, 1))
        assertFalse(isMarketplacePriceUrlAllowed("https", "www.discogs.com", "/login", "123", "1", 123, 1))
    }
    private val vg = "Very Good (VG)"
    private val nm = "Near Mint (NM or M-)"
    private fun snapshot(value: Double) = ActiveMarketplaceConditionPrices(mapOf(vg to value), mapOf("$vg||$nm" to value))

    @Test fun freshScanReplacesPriceEvenWhenItRises() {
        var clock = 1_000L
        val cache = MarketplacePriceCache { clock }
        cache.put(1, snapshot(10.0))
        clock += 500
        cache.put(1, snapshot(25.0))
        assertEquals(25.0, cache.get(1)!!.prices!!.priceFor(vg, nm)!!, 0.0)
        assertEquals(clock, cache.get(1)!!.checkedAtMillis)
    }

    @Test fun disappearedListingsAreRemovedAndEmptySuccessIsCached() {
        val cache = MarketplacePriceCache { 1_000L }
        cache.put(1, snapshot(10.0))
        cache.put(1, ActiveMarketplaceConditionPrices())
        assertNotNull(cache.get(1))
        assertNull(cache.get(1)!!.prices!!.priceFor(vg, nm))
    }

    @Test fun lookupDoesNotRefreshTimestampAndOtherReleaseCannotExtendExpiry() {
        var clock = 1_000L
        val cache = MarketplacePriceCache { clock }
        cache.put(1, snapshot(10.0))
        clock += MARKETPLACE_PRICE_CACHE_TTL_MS - 1
        assertNotNull(cache.get(1))
        cache.put(2, snapshot(30.0))
        clock++
        assertNull(cache.get(1))
        assertNotNull(cache.get(2))
    }

    @Test fun exactSleeveNeverFallsBackToCheaperMediaOnlyPrice() {
        val prices = ActiveMarketplaceConditionPrices(mapOf(vg to 5.0), mapOf("$vg||$nm" to 20.0))
        assertEquals(5.0, prices.priceFor(vg, "")!!, 0.0)
        assertEquals(20.0, prices.priceFor(vg, nm)!!, 0.0)
        assertNull(prices.priceFor(vg, "Generic"))
        assertNull(prices.priceFor(nm, nm))
    }

    @Test fun minimaAreMergedAcrossPagesWithinOneScan() {
        val combined = snapshot(30.0).plusPage(snapshot(20.0)).plusPage(snapshot(40.0))
        assertEquals(20.0, combined.priceFor(vg, nm)!!, 0.0)
    }

    @Test fun invalidValuesAreNeverRecommended() {
        for (value in listOf(Double.NaN, Double.POSITIVE_INFINITY, -1.0, 0.0)) {
            assertNull(snapshot(value).priceFor(vg, nm))
        }
    }

    @Test fun cacheIsBoundedAndClockRollbackExpiresData() {
        var clock = 1000L
        val cache = MarketplacePriceCache { clock }
        for (id in 1L..65L) cache.put(id, snapshot(id.toDouble()))
        assertNull(cache.get(1))
        assertNotNull(cache.get(65))
        clock--
        assertNull(cache.get(65))
    }
}
