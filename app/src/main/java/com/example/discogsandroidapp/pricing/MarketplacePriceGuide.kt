package com.example.discogsandroidapp.pricing

import com.example.discogsandroidapp.data.ReleasePriceSummary

internal data class MarketplacePageRange(val lowest: Double, val average: Double, val highest: Double, val count: Int)

/** Prices are already normalized to USD by the page reader; no requests here. */
internal fun marketplacePageRange(prices: List<Double>): MarketplacePageRange? {
    val valid = prices.take(250).filter { it.isFinite() && it > 0.0 }
    if (valid.isEmpty()) return null
    // Divide before summing to avoid overflowing on large values.
    return MarketplacePageRange(valid.min(), valid.sumOf { it / valid.size }, valid.max(), valid.size)
}

internal data class PriceGuideValues(
    val low: Double?, val middle: Double?, val high: Double?,
    val lowLabel: String, val middleLabel: String, val highLabel: String,
    val source: String, val currency: String
)

internal fun priceGuideValues(
    summary: ReleasePriceSummary,
    livePrices: List<Double>,
    pricingInfo: ListingPricingInfo?
): PriceGuideValues {
    val range = marketplacePageRange(livePrices)
    if (range != null) {
        val source = if (pricingInfo?.usingSavedPrices == true) "Saved live prices" else "Live prices"
        return PriceGuideValues(range.lowest, range.average, range.highest,
            "Low", "Average", "High",
            "$source · ${range.count} priced listings on page 1 (up to 250) · all conditions · shipping excluded",
            "USD")
    }
    val loading = if (pricingInfo?.status == MarketplaceUiPriceStatus.LOADING) " · Checking live prices…" else ""
    return PriceGuideValues(summary.low, summary.median, summary.high,
        "Low", "Median", "High", "Based on pricing algorithm$loading", summary.currency)
}
