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

    @Test fun guideNeverUsesMarketplacePagePrices() {
        val summary = ReleasePriceSummary(low = 7.50, median = 52.49, high = 142.48, lowestAskingPrice = 11.47, numForSale = 21)
        val guide = priceGuideValues(summary, listOf(1.0, 500.0, 1000.0), info(MarketplaceUiPriceStatus.FRESH))

        assertEquals(7.50, guide.low!!, 0.0)
        assertEquals(31.98, guide.middle!!, 0.01)
        assertEquals(142.48, guide.high!!, 0.0)
        assertEquals("Typical", guide.middleLabel)
        assertTrue(guide.source.startsWith("Discogs API"))
    }

    @Test fun inflatedHistoricalMiddleIsPulledTowardCurrentMarketFloor() {
        val rumours = ReleasePriceSummary(median = 52.49, high = 142.48, lowestAskingPrice = 11.47, numForSale = 21)
        val stills = ReleasePriceSummary(median = 7.00, high = 19.00, lowestAskingPrice = 0.99, numForSale = 81)
        val gratefulDead = ReleasePriceSummary(median = 57.65, high = 156.47, lowestAskingPrice = 15.00, numForSale = 94)
        val neilYoung = ReleasePriceSummary(median = 112.41, high = 305.11, lowestAskingPrice = 1.12, numForSale = 30)

        assertEquals(31.98, apiOnlyTypicalPrice(rumours)!!, 0.01)
        assertEquals(2.72, apiOnlyTypicalPrice(stills)!!, 0.01)
        assertEquals(37.50, apiOnlyTypicalPrice(gratefulDead)!!, 0.01)
        assertEquals(65.20, apiOnlyTypicalPrice(neilYoung)!!, 0.01)
    }

    @Test fun goodHistoricalMiddleIsPreservedWhenCurrentFloorDoesNotStronglyDisagree() {
        val aja = ReleasePriceSummary(median = 24.71, high = 67.06, lowestAskingPrice = 13.37)
        val teddy = ReleasePriceSummary(median = 4.81, high = 13.06, lowestAskingPrice = 1.60)

        assertEquals(24.71, apiOnlyTypicalPrice(aja)!!, 0.0)
        assertEquals(4.81, apiOnlyTypicalPrice(teddy)!!, 0.0)
    }

    @Test fun currentFloorAboveHistoricalMiddleRaisesTheCenterConservatively() {
        val summary = ReleasePriceSummary(median = 17.38, high = 47.19, lowestAskingPrice = 24.0)
        assertEquals(30.0, apiOnlyTypicalPrice(summary)!!, 0.0)
    }

    @Test fun invalidOrMissingCurrentFloorLeavesHistoricalMiddleAlone() {
        for (floor in listOf<Double?>(null, 0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertEquals(31.50, apiOnlyTypicalPrice(ReleasePriceSummary(median = 31.50, lowestAskingPrice = floor))!!, 0.0)
        }
    }

    @Test fun adjustedCenterNeverExceedsHigh() {
        val summary = ReleasePriceSummary(median = 20.0, high = 25.0, lowestAskingPrice = 30.0)
        assertEquals(25.0, apiOnlyTypicalPrice(summary)!!, 0.0)
    }

    @Test fun algorithmRemainsVisibleForLoadingFailuresAndEmptyResults() {
        for (status in listOf(MarketplaceUiPriceStatus.LOADING, MarketplaceUiPriceStatus.NO_MATCH,
            MarketplaceUiPriceStatus.VERIFICATION_REQUIRED, MarketplaceUiPriceStatus.RATE_LIMITED)) {
            val guide = priceGuideValues(algorithm, emptyList(), info(status))
            assertEquals(algorithm.low, guide.low)
            assertEquals(algorithm.median, guide.middle)
            assertEquals(algorithm.high, guide.high)
            assertTrue(guide.source.startsWith("Discogs API"))
        }
    }

    @Test fun savedOrFreshLivePricesDoNotReplaceTheApiGuide() {
        for (status in listOf(MarketplaceUiPriceStatus.FRESH, MarketplaceUiPriceStatus.RATE_LIMITED)) {
            val guide = priceGuideValues(algorithm, listOf(12.0, 50.0, 500.0), info(status, saved = true))
            assertEquals(algorithm.low, guide.low)
            assertEquals(algorithm.median, guide.middle)
            assertEquals(algorithm.high, guide.high)
            assertEquals("Typical", guide.middleLabel)
        }
    }


    @Test fun calibratedTypicalPriceHandlesRecentExamplesWithoutMarketplaceWebView() {
        val kiss = ReleasePriceSummary(median = 148.75, high = 403.75, lowestAskingPrice = 6.99, numForSale = 82)
        val smiths = ReleasePriceSummary(median = 100.00, high = 201.12, lowestAskingPrice = 80.00, numForSale = 9)
        val dokken = ReleasePriceSummary(median = 55.59, high = 150.88, lowestAskingPrice = 18.00, numForSale = 45)
        val alice = ReleasePriceSummary(median = 18.95, high = 51.41, lowestAskingPrice = 3.00, numForSale = 72)
        val bill = ReleasePriceSummary(median = 19.50, high = 52.94, lowestAskingPrice = 10.00, numForSale = 22)

        assertEquals(19.22, apiOnlyTypicalPrice(kiss)!!, 0.01)
        assertEquals(90.00, apiOnlyTypicalPrice(smiths)!!, 0.01)
        assertEquals(21.60, apiOnlyTypicalPrice(dokken)!!, 0.01)
        assertEquals(8.25, apiOnlyTypicalPrice(alice)!!, 0.01)
        assertEquals(30.00, apiOnlyTypicalPrice(bill, haveCount = 2325, wantCount = 1462)!!, 0.01)
    }

    @Test fun oldCachesWithoutIndividualPricesStillDecode() {
        val restored = Json.decodeFromString<ActiveMarketplaceConditionPrices>("""{"mediaLowest":{"Very Good (VG)":10.0}}""")
        assertTrue(restored.firstPagePrices.isEmpty())
        assertTrue(restored.mediaPriceSamples.isEmpty())
        assertTrue(restored.mediaSleevePriceSamples.isEmpty())
        assertEquals(10.0, restored.mediaLowest["Very Good (VG)"]!!, 0.0)
    }

    @Test fun cachedSnapshotsPreserveIndividualComparablePrices() {
        val source = ActiveMarketplaceConditionPrices(
            mediaPriceSamples = mapOf("Very Good (VG)" to listOf(1.0, 10.0, 20.0)),
            mediaSleevePriceSamples = mapOf("Very Good (VG)||Generic" to listOf(1.0, 10.0)))
        val restored = Json.decodeFromString<ActiveMarketplaceConditionPrices>(
            Json.encodeToString(ActiveMarketplaceConditionPrices.serializer(), source))
        assertEquals(source, restored)
    }
}
