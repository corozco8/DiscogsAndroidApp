package com.example.discogsandroidapp

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class MarketplaceExchangeRatesTest {
    private val today = LocalDate.of(2026, 9, 24).toEpochDay() * 86_400_000L

    @Test fun ratesAreInvertedToConvertToUsd() {
        val rates = marketplaceUsdRates(listOf(MarketplaceFxRate("2026-09-24", "USD", "CAD", 1.25)), today)
        assertEquals(0.8, rates["CAD"]!!, 0.0)
        assertEquals(1.0, rates["USD"]!!, 0.0)
    }

    @Test fun invalidAndOldRatesAreExcluded() {
        val rows = listOf(
            MarketplaceFxRate("2026-09-16", "USD", "EUR", 0.9),
            MarketplaceFxRate("2026-09-25", "USD", "GBP", 0.8),
            MarketplaceFxRate("bad", "USD", "JPY", 140.0),
            MarketplaceFxRate("2026-09-24", "EUR", "CAD", 1.5),
            MarketplaceFxRate("2026-09-24", "USD", "AUD", 0.0),
            MarketplaceFxRate("2026-09-24", "USD", "NZD", Double.NaN),
            MarketplaceFxRate("2026-09-24", "USD", "CHF", Double.POSITIVE_INFINITY)
        )
        assertEquals(mapOf("USD" to 1.0), marketplaceUsdRates(rows, today))
    }

    @Test fun recentWeekendRatesRemainUsable() {
        val rates = marketplaceUsdRates(listOf(MarketplaceFxRate("2026-09-18", "USD", "EUR", 0.8)), today)
        assertEquals(1.25, rates["EUR"]!!, 0.0)
    }
}
