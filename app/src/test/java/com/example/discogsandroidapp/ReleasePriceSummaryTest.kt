package com.example.discogsandroidapp

import org.junit.Assert.assertEquals
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
    fun fallbackRetainsPreviousPremiumSleeveRule() {
        val summary = ReleasePriceSummary(
            priceSuggestions = suggestions()
        )

        assertEquals(
            34.5,
            summary.fallbackRecommendedPriceFor(
                "Very Good Plus (VG+)",
                "Very Good Plus (VG+)"
            )!!,
            0.0
        )
    }
}
