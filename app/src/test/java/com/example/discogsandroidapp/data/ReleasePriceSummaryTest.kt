package com.example.discogsandroidapp.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class ReleasePriceSummaryTest {
    private fun suggestions() = PriceSuggestions(
        veryGoodPlus = PriceSuggestionValue(30.0, "USD"),
        veryGood = PriceSuggestionValue(20.0, "USD"),
        goodPlus = PriceSuggestionValue(15.0, "USD"),
        good = PriceSuggestionValue(10.0, "USD"),
        fair = PriceSuggestionValue(7.0, "USD"),
        poor = PriceSuggestionValue(5.0, "USD")
    )

    @Test
    fun liveMarketplacePriceWinsWhenAvailable() {
        val summary = ReleasePriceSummary(
            priceSuggestions = suggestions(),
            isAlbumRelease = false,
            activeMediaListingCounts = mapOf("Very Good Plus (VG+)" to 2),
            activeMediaLowestPrices = mapOf(
                "Very Good Plus (VG+)" to 25.0
            )
        )

        assertEquals(
            25.0,
            summary.recommendedPriceFor(
                "Very Good Plus (VG+)",
                "Very Good Plus (VG+)"
            )!!,
            0.0
        )
    }

    @Test
    fun originalAlgorithmIsUsedWhenLiveMarketplacePriceIsUnavailable() {
        val summary = ReleasePriceSummary(
            priceSuggestions = suggestions(),
            lowestAskingPrice = 22.0,
            numForSale = 8
        )

        val fallback = summary.fallbackRecommendedPriceFor(
            "Very Good (VG)",
            "Very Good (VG)"
        )
        val recommendation = summary.recommendedPriceFor(
            "Very Good (VG)",
            "Very Good (VG)"
        )

        assertNotNull(fallback)
        assertEquals(fallback!!, recommendation!!, 0.0)
    }

    @Test
    fun fallbackUsesAModestPremiumForNearMintSleeves() {
        val summary = ReleasePriceSummary(
            priceSuggestions = suggestions()
        )

        assertEquals(
            31.50,
            summary.fallbackRecommendedPriceFor(
                "Very Good Plus (VG+)",
                "Near Mint (NM or M-)"
            )!!,
            0.0
        )
    }

    private val gp = "Good Plus (G+)"
    private val vg = "Very Good (VG)"
    private val vgp = "Very Good Plus (VG+)"

    private fun live(prices: Map<String, Double>) = ReleasePriceSummary(
        priceSuggestions = suggestions(),
        isAlbumRelease = false,
        activeMediaLowestPrices = prices,
        activeMediaListingCounts = prices.mapValues { 2 }
    )

    @Test fun similarEqualAndHigherGoodPlusPricesUseSixtyPercentOfHigherGrade() {
        for (better in listOf(vg, vgp)) {
            for (price in listOf(9.0, 9.5, 10.0, 12.0)) {
                val summary = live(mapOf(gp to price, better to 10.0))
                assertNull(summary.currentListingPriceFor(gp))
                assertEquals(6.0, summary.liveGradeEstimateFor(gp)!!.price, 0.0)
                assertEquals(6.0, summary.recommendedPriceFor(gp)!!, 0.0)
            }
        }
    }

    @Test fun SufficientlyCheaperPriceRemainsLive() {
        assertEquals(8.99, live(mapOf(gp to 8.99, vg to 10.0)).currentListingPriceFor(gp)!!, 0.0)
    }

    @Test fun missingOrUntrustedHigherGradeDoesNotRejectLivePrice() {
        val summary = live(mapOf(gp to 12.0, vg to 10.0))
            .copy(activeMediaListingCounts = mapOf(gp to 2, vg to 1))
        assertEquals(12.0, summary.currentListingPriceFor(gp)!!, 0.0)
        assertEquals(12.0, live(mapOf(gp to 12.0)).currentListingPriceFor(gp)!!, 0.0)
    }

    @Test fun rejectedVgSkipsGradeRatioEstimate() {
        val summary = live(mapOf(vg to 11.0, vgp to 10.0))
        assertNull(summary.liveGradeEstimateFor(vg))
        assertEquals(summary.fallbackRecommendedPriceFor(vg), summary.recommendedPriceFor(vg))
    }

    @Test fun goodPlusEstimateWorksWithoutHistoricalFallback() {
        val summary = live(mapOf(gp to 12.0, vg to 10.0)).copy(priceSuggestions = null)
        assertEquals(6.0, summary.recommendedPriceFor(gp)!!, 0.0)
    }

    @Test fun albumComparisonsKeepTargetSleevePolicy() {
        val summary = live(mapOf(vg to 12.0, vgp to 10.0)).copy(
            isAlbumRelease = true,
            activeMediaSleeveLowestPrices = mapOf("$vg||$vg" to 12.0, "$vgp||Poor (P)" to 10.0),
            activeMediaSleeveListingCounts = mapOf("$vg||$vg" to 2, "$vgp||Poor (P)" to 2)
        )
        assertEquals(12.0, summary.currentListingPriceFor(vg, vg)!!, 0.0)
        assertNull(summary.currentListingPriceFor(vg, "Poor (P)"))
    }

    @Test fun goodPlusUsesCheapestTrustedHigherGrade() {
        val summary = live(mapOf(gp to 20.0, vg to 12.0, vgp to 10.0))
        assertEquals(6.0, summary.recommendedPriceFor(gp)!!, 0.0)
    }

    @Test fun gradeEstimatesKeepSixtyPercentPerStep() {
        val nm = "Near Mint (NM or M-)"
        val summary = live(mapOf(nm to 39.99))
        assertEquals(23.99, summary.recommendedPriceFor(vgp)!!, 0.0)
        assertEquals(14.40, summary.recommendedPriceFor(vg)!!, 0.0)
        assertEquals(10.0, live(mapOf(vgp to 6.0)).recommendedPriceFor(nm)!!, 0.0)
    }

    @Test fun missingVgPlusUsesTheMidpointOfNearMintAndVeryGoodAsks() {
        val nm = "Near Mint (NM or M-)"
        val summary = live(mapOf(nm to 20.0, vg to 8.0))
        val estimate = summary.liveGradeEstimateFor(vgp)!!
        assertEquals(14.0, estimate.price, 0.0)
        assertEquals(nm, estimate.sourceCondition)
        assertEquals(vg, estimate.secondSourceCondition)
        assertEquals(14.0, summary.recommendedPriceFor(vgp)!!, 0.0)
    }
    @Test fun aMatchingVgPlusAskWinsOverInterpolation() {
        val summary = live(mapOf("Near Mint (NM or M-)" to 20.0, vgp to 12.0, vg to 8.0))
        assertEquals(12.0, summary.recommendedPriceFor(vgp)!!, 0.0)
        assertNull(summary.liveGradeEstimateFor(vgp))
    }
    @Test fun contradictoryOrTooCloseGradesDoNotCreateAnInterpolatedPrice() {
        val nm = "Near Mint (NM or M-)"
        assertNull(live(mapOf(nm to 10.0, vg to 20.0)).liveGradeEstimateFor(vgp))
        assertNull(live(mapOf(nm to 20.0, vg to 19.0)).liveGradeEstimateFor(vgp))
    }
    @Test fun interpolationRetainsSleeveExclusions() {
        val nm = "Near Mint (NM or M-)"
        val summary = live(mapOf(nm to 20.0, vg to 8.0)).copy(isAlbumRelease = true,
            activeMediaSleeveLowestPrices = mapOf("$nm||$vgp" to 20.0, "$vg||Poor (P)" to 8.0),
            activeMediaSleeveListingCounts = mapOf("$nm||$vgp" to 2, "$vg||Poor (P)" to 2))
        assertEquals(12.0, summary.liveGradeEstimateFor(vgp, vgp)!!.price, 0.0)
    }
}
