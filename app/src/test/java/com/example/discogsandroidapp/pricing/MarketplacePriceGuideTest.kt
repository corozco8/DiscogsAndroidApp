package com.example.discogsandroidapp.pricing

import com.example.discogsandroidapp.data.ReleasePriceSummary

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class MarketplacePriceGuideTest {
    private val algorithm = ReleasePriceSummary(low = 2.0, median = 15.0, high = 60.0)
    private fun info(status: MarketplaceUiPriceStatus, saved: Boolean = false) = ListingPricingInfo(status, "", saved)

    @Test fun averageWeightsEachListingInsteadOfConditionGroups() {
        val range = marketplacePageRange(listOf(10.0, 10.0, 100.0))!!
        assertEquals(10.0, range.lowest, 0.0)
        assertEquals(40.0, range.average, 0.0001)
        assertEquals(100.0, range.highest, 0.0)
        assertEquals(3, range.count)
    }

    @Test fun ignoresInvalidAmountsAndHandlesOneOrNoListing() {
        val range = marketplacePageRange(listOf(Double.NaN, -1.0, 0.0, Double.POSITIVE_INFINITY, 12.50))!!
        assertEquals(MarketplacePageRange(12.50, 12.50, 12.50, 1), range)
        assertNull(marketplacePageRange(emptyList()))
        assertNull(marketplacePageRange(listOf(0.0, Double.NaN)))
    }

    @Test fun neverIncludesMoreThan250Listings() {
        val range = marketplacePageRange(List(250) { 10.0 } + 100_000.0)!!
        assertEquals(250, range.count)
        assertEquals(10.0, range.highest, 0.0)
        assertEquals(10.0, range.average, 0.0001)
    }

    @Test fun algorithmRemainsVisibleForLoadingFailuresAndEmptyResults() {
        for (status in listOf(MarketplaceUiPriceStatus.LOADING, MarketplaceUiPriceStatus.NO_MATCH,
            MarketplaceUiPriceStatus.VERIFICATION_REQUIRED, MarketplaceUiPriceStatus.RATE_LIMITED)) {
            val guide = priceGuideValues(algorithm, emptyList(), info(status))
            assertEquals(algorithm.low, guide.low)
            assertEquals(algorithm.median, guide.middle)
            assertEquals(algorithm.high, guide.high)
            assertTrue(guide.source.startsWith("Based on pricing algorithm"))
        }
    }

    @Test fun liveResultsReplaceAllThreeValuesAndClearlyLabelTheAverage() {
        val guide = priceGuideValues(algorithm, listOf(10.0, 20.0, 90.0), info(MarketplaceUiPriceStatus.FRESH))
        assertEquals(10.0, guide.low!!, 0.0)
        assertEquals(40.0, guide.middle!!, 0.0001)
        assertEquals(90.0, guide.high!!, 0.0)
        assertEquals("Average", guide.middleLabel)
        assertTrue(guide.source.contains("page 1 (up to 250)"))
        assertTrue(guide.source.contains("all conditions"))
    }

    @Test fun savedLivePricesRemainVisibleWhileRefreshIsBlocked() {
        val guide = priceGuideValues(algorithm, listOf(12.0), info(MarketplaceUiPriceStatus.RATE_LIMITED, saved = true))
        assertEquals(12.0, guide.middle!!, 0.0)
        assertTrue(guide.source.startsWith("Saved live prices"))
    }

    @Test fun oldCachesWithoutIndividualPricesStillDecode() {
        val restored = Json.decodeFromString<ActiveMarketplaceConditionPrices>("""{"mediaLowest":{"Very Good (VG)":10.0}}""")
        assertTrue(restored.firstPagePrices.isEmpty())
        assertEquals(10.0, restored.mediaLowest["Very Good (VG)"]!!, 0.0)
    }
}
